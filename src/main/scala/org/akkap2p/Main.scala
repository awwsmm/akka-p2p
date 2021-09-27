package org.akkap2p

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.{ActorSystem, Scheduler}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives
import com.typesafe.scalalogging.StrictLogging
import org.akkap2p.actors.User
import org.akkap2p.model.Address
import pureconfig.ConfigSource

object Main extends Directives with StrictLogging {

  final case class Config(httpHost: String, httpPort: Int, peers: String)

  /** Configuration for akka-p2p read from application.conf. */
  private[this] implicit val config: Config = {
    import pureconfig.generic.auto._
    ConfigSource.default.load[Config] match {
      case Right(value) => value
      case Left(reasons) =>
        val msg = s"Cannot start App. Invalid config: $reasons"
        logger.error(msg)
        throw new IllegalArgumentException(msg)
    }
  }

  private[this] implicit val system: ActorSystem[User.Command] = ActorSystem(User.behavior, "app")
  private[this] implicit val executor: ExecutionContextExecutor = system.executionContext
  private[this] implicit val scheduler: Scheduler = system.scheduler

  // TODO assess required libraries in build.sbt

  // TODO clean up all these .seconds and askTimeout timeouts -- into config

  def main(args: Array[String]): Unit = {

    val bindingFuture = Http()
      .newServerAt(config.httpHost, config.httpPort)
      .adaptSettings(_.mapWebsocketSettings(_.withPeriodicKeepAliveMaxIdle(30.seconds)))
      .bind(API().all)

    logger.info(s"Listening on ${config.httpHost}:${config.httpPort}")

    // connect to all preconfigured peers
    config.peers.split(",").filter(_.nonEmpty).flatMap(Address.fromString).foreach(Actions.connect)

    // run the Terminal
    Terminal().run()

    // TODO fix this so it shuts down cleanly
    bindingFuture.flatMap { binding =>
      system.ref ! User.Disconnect
      binding.unbind()

    }.onComplete { _ =>
      system.terminate()
    }
  }
}
