package org.akkap2p

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.Success

import akka.actor
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.{ActorSystem, Scheduler}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives
import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging
import org.akkap2p.actors.User
import org.akkap2p.model.{Address, AddressedMessage}
import org.scalactic.TypeCheckedTripleEquals._
import pureconfig.ConfigSource

// TODO assess required libraries in build.sbt

// TODO clean up all these .seconds and askTimeout timeouts -- into config

object Main extends Directives with StrictLogging {

  final case class Config(httpHost: String, httpPort: Int, peers: String)

  // Configuration for akka-p2p read from application.conf
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

  def main(args: Array[String]): Unit = {

    val prompt = "\nakka-p2p> "

    // what do we do when we receive a message from a peer?
    def onReceive(addressedMessage: AddressedMessage): Unit = {
      val AddressedMessage(address, message) = addressedMessage
      print(s"""$address: "$message"\n$prompt""")
    }

    implicit val system: ActorSystem[User.Command] = ActorSystem(User.behavior, "app")

    // define the akka-p2p server
    val bindingFuture = Http()
      .newServerAt(config.httpHost, config.httpPort)
      .adaptSettings(_.mapWebsocketSettings(_.withPeriodicKeepAliveMaxIdle(30.seconds)))
      .bind(API(onReceive).all)

    logger.info(s"Listening on ${config.httpHost}:${config.httpPort}")

    // connect to all preconfigured peers
    config.peers.split(",").filter(_.nonEmpty).flatMap(Address.fromString).foreach(Actions.connect(_, onReceive))

    // run the Terminal (this blocks until the user `quit`s)
    Terminal(prompt, onReceive).run()
    Actions.disconnectAll()

    implicit val executor: ExecutionContextExecutor = system.executionContext

    // if we're here, the user must have `quit` the terminal, above
    bindingFuture.flatMap { binding =>

      def close(): Future[Unit] = {

        implicit val timeout: Timeout = 5.seconds
        implicit val scheduler: Scheduler = system.scheduler
        val futurePeerGroups = system.ref ? User.GetPeers

        // We do not terminate until there are no more connected peers.
        futurePeerGroups.map { groups =>
          groups.find(_.name === "connected") match {
            case Some(connected) if connected.addresses.nonEmpty =>
              throw new IllegalStateException("""PeerGroup "connected" is not empty.""")
            case None =>
              throw new IllegalStateException("""Expected "connected" PeerGroup not found""")
            case _ => ()
          }
        }
      }

      // We wait for all connections to disconnect, then we terminate the akka-p2p server
      implicit val classicScheduler: actor.Scheduler = system.classicSystem.scheduler
      akka.pattern.retry(close, 10, 1.seconds).andThen {
        case Success(_) => binding.terminate(30.seconds)
      }

    }.onComplete { _ =>
      // finally, we terminate the ActorSystem and quit
      system.terminate()
    }
  }
}
