package org.thp.thehive.services

import java.io.NotSerializableException

import scala.collection.immutable
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Random, Try}

import play.api.libs.json.Json
import play.api.{Configuration, Logger}

import akka.actor.{actorRef2Scala, Actor, ActorIdentity, ActorRef, ActorSystem, Cancellable, Identify, PoisonPill, Props}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{Put, Send, Subscribe}
import akka.pattern.{ask, AskTimeoutException}
import akka.serialization.Serializer
import akka.util.Timeout
import javax.inject.{Inject, Singleton}
import org.thp.scalligraph.NotFoundError
import org.thp.scalligraph.auth.AuthContext
import org.thp.scalligraph.models.Database
import org.thp.thehive.services.StreamActor.{Commit, GetStreamMessages}

trait StreamMessage extends Serializable

case class AuditStreamMessage(id: String*) extends StreamMessage

object StreamActor {
  /* Ask messages, wait if there is no ready messages */
  case object GetStreamMessages extends StreamMessage
  case object Commit            extends StreamMessage
}

/**
  * This actor receive message generated locally and when aggregation is finished (http request is over) send the message
  * to global stream actor.
  */
class StreamActor(
    authContext: AuthContext,
    refresh: FiniteDuration,
    maxWait: FiniteDuration,
    graceDuration: FiniteDuration,
    keepAlive: FiniteDuration,
    auditSrv: AuditSrv,
    db: Database
) extends Actor {
  import EventSrv._
  import StreamActor._
  import context.dispatcher

  lazy val logger        = Logger(s"${getClass.getName}.$self")
  val mediator: ActorRef = DistributedPubSub(context.system).mediator

  override def preStart(): Unit = {
    mediator ! Subscribe(STREAM, self)
    super.preStart()
  }

  override def receive: Receive = {
    val keepAliveTimer = context.system.scheduler.scheduleOnce(keepAlive, self, PoisonPill)
    receive(Nil, keepAliveTimer)
  }

  def receive(messages: Seq[String], keepAliveTimer: Cancellable): Receive = {
    case GetStreamMessages =>
      logger.debug(s"[$self] GetStreamMessages")
      // rearm keepalive
      keepAliveTimer.cancel()
      val newKeepAliveTimer = context.system.scheduler.scheduleOnce(keepAlive, self, PoisonPill)
      val commitTimer       = context.system.scheduler.scheduleOnce(refresh, self, Commit)
      val graceTimer =
        if (messages.isEmpty) None
        else Some(context.system.scheduler.scheduleOnce(graceDuration, self, Commit))
      context.become(receive(messages, sender, newKeepAliveTimer, commitTimer, graceTimer))

    case AuditStreamMessage(ids @ _*) =>
      db.transaction { implicit graph =>
        val visibleIds = auditSrv
          .get(ids)
          .visible(authContext)
          .toList()
          .map(_._id)
        logger.debug(s"[$self] AuditStreamMessage $ids => $visibleIds")
        if (visibleIds.nonEmpty) {
          context.become(receive(messages ++ visibleIds, keepAliveTimer))
        }
      }
  }

  def receive(
      messages: Seq[String],
      requestActor: ActorRef,
      keepAliveTimer: Cancellable,
      commitTimer: Cancellable,
      graceTimer: Option[Cancellable]
  ): Receive = {
    case GetStreamMessages =>
      logger.debug(s"[$self] GetStreamMessages")
      // rearm keepalive
      keepAliveTimer.cancel()
      val newKeepAliveTimer = context.system.scheduler.scheduleOnce(keepAlive, self, PoisonPill)
      commitTimer.cancel()
      val newCommitTimer = context.system.scheduler.scheduleOnce(refresh, self, Commit)
      graceTimer.foreach(_.cancel())
      val newGraceTimer =
        if (messages.isEmpty) None
        else Some(context.system.scheduler.scheduleOnce(graceDuration, self, Commit))
      context.become(receive(messages, sender, newKeepAliveTimer, newCommitTimer, newGraceTimer))

    case Commit =>
      logger.debug(s"[$self] Commit")
      commitTimer.cancel()
      graceTimer.foreach(_.cancel())
      requestActor ! AuditStreamMessage(messages: _*)
      context.become(receive(Nil, keepAliveTimer))

    case AuditStreamMessage(ids @ _*) =>
      db.transaction { implicit graph =>
        val visibleIds = auditSrv
          .get(ids)
          .visible(authContext)
          .toList()
          .map(_._id)
        logger.debug(s"[$self] AuditStreamMessage $ids => $visibleIds")
        if (visibleIds.nonEmpty) {

          graceTimer.foreach(_.cancel())
          val newGraceTimer = context.system.scheduler.scheduleOnce(graceDuration, self, Commit)
          if (messages.isEmpty) {
            commitTimer.cancel()
            val newCommitTimer = context.system.scheduler.scheduleOnce(maxWait, self, Commit)
            context.become(receive(messages ++ visibleIds, requestActor, keepAliveTimer, newCommitTimer, Some(newGraceTimer)))
          } else {
            context.become(receive(messages ++ visibleIds, requestActor, keepAliveTimer, commitTimer, Some(newGraceTimer)))
          }
        }
      }
  }
}

@Singleton
class StreamSrv @Inject()(configuration: Configuration, auditSrv: AuditSrv, db: Database, system: ActorSystem, implicit val ec: ExecutionContext) {

  lazy val logger                              = Logger(getClass)
  val streamLength                             = 20
  val alphanumeric: immutable.IndexedSeq[Char] = ('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9')
  val mediator: ActorRef                       = DistributedPubSub(system).mediator
  val refresh: FiniteDuration                  = configuration.get[FiniteDuration]("stream.longPolling.refresh")
  val maxWait: FiniteDuration                  = configuration.get[FiniteDuration]("stream.longPolling.maxWait")
  val graceDuration: FiniteDuration            = configuration.get[FiniteDuration]("stream.longPolling.graceDuration")
  val keepAlive: FiniteDuration                = configuration.get[FiniteDuration]("stream.longPolling.keepAlive")

  def generateStreamId(): String = Seq.fill(streamLength)(alphanumeric(Random.nextInt(alphanumeric.size))).mkString

  def isValidStreamId(streamId: String): Boolean = streamId.length == streamLength && streamId.forall(alphanumeric.contains)

  def create(implicit authContext: AuthContext): String = {
    val streamId = generateStreamId()
    val streamActor =
      system.actorOf(Props(classOf[StreamActor], authContext, refresh, maxWait, graceDuration, keepAlive, auditSrv, db), s"stream-$streamId")
    logger.debug(s"Register stream actor ${streamActor.path}")
    mediator ! Put(streamActor)
    streamId
  }

  def get(streamId: String): Future[Seq[String]] = {
    implicit val timeout: Timeout = Timeout(refresh + 1.second)
    // Check if stream actor exists
    mediator
      .ask(Send(s"/user/stream-$streamId", Identify(1), localAffinity = false))(Timeout(2.seconds))
      .flatMap {
        case ActorIdentity(1, Some(streamActor)) =>
          logger.debug(s"Stream actor found for stream $streamId")
          (streamActor ? StreamActor.GetStreamMessages)
            .map {
              case AuditStreamMessage(ids @ _*) => ids
              case _                            => Nil
            }
        case other => Future.failed(NotFoundError(s"Stream $streamId doesn't exist: $other"))
      }
      .recoverWith {
        case _: AskTimeoutException => Future.failed(NotFoundError(s"Stream $streamId doesn't exist"))
      }
  }
}

class StreamSerializer extends Serializer {
  def identifier: Int = 226591535
  def includeManifest = false

  /**
    * Serializes the given object into an Array of Byte
    */
  def toBinary(o: AnyRef): Array[Byte] =
    o match {
      case AuditStreamMessage(ids @ _*) => Json.toJson(ids).toString.getBytes
      case GetStreamMessages            => "GetStreamMessages".getBytes
      case Commit                       => "Commit".getBytes
      case _                            => Array.empty[Byte] // Not serializable
    }

  /**
    * Produces an object from an array of bytes, with an optional type-hint;
    * the class should be loaded using ActorSystem.dynamicAccess.
    */
  @throws(classOf[NotSerializableException])
  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef =
    new String(bytes) match {
      case "GetStreamMessages" => GetStreamMessages
      case "Commit"            => Commit
      case s                   => Try(AuditStreamMessage(Json.parse(s).as[Seq[String]]: _*)).getOrElse(throw new NotSerializableException)
    }
}
