package org.akkap2p

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

import akka.actor
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.{ActorSystem, Scheduler}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging
import org.akkap2p.actors.User

object Server extends StrictLogging {

  def start(api: Route)(implicit system: ActorSystem[User.Command], config: Config): Future[Http.ServerBinding] = {
    logger.info(s"Starting akka-p2p server on ${config.httpHost}:${config.httpPort}")

    Http()
      .newServerAt(config.httpHost, config.httpPort)
      .adaptSettings(_.mapWebsocketSettings(_.withPeriodicKeepAliveMaxIdle(30.seconds)))
      .bind(api)
  }

  def stop(bindingFuture: Future[Http.ServerBinding])(implicit system: ActorSystem[User.Command], config: Config): Future[Unit] = {
    logger.info(s"Stopping akka-p2p server on ${config.httpHost}:${config.httpPort}")

    implicit val ec: ExecutionContext = system.executionContext

    // if we're here, the user must have `quit` the terminal, above
    bindingFuture.flatMap { binding =>

      def tryToStop(): Future[Unit] = {

        implicit val timeout: Timeout = config.timeouts.getPeers
        implicit val scheduler: Scheduler = system.scheduler
        val futurePeerGroups = system.ref ? User.GetPeers

        // We do not terminate until there are no more connected peers.
        futurePeerGroups.map { groups =>
          groups.find(_.name == "connected") match {
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
      akka.pattern.retry(tryToStop, 10, 1.seconds).andThen {
        case Success(_) => binding.terminate(30.seconds)
      }
    }
  }

}
