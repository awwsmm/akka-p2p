package org.akkap2p

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.io.StdIn
import scala.util.{Failure, Success, Try}

import akka.actor.typed._
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives
import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging
import peers.{Address, User}
import pureconfig._
import pureconfig.generic.auto._
import upickle.default._

object App extends App with Directives with StrictLogging with JSONSupport {

  // TODO send logs to file instead of to stdout

  // TODO assess required libraries in build.sbt

  final case class AppConfig(httpHost: String, httpPort: Int, peers: String)

  val config: AppConfig =
    ConfigSource.default.load[AppConfig] match {
      case Right(value) => value
      case Left(reasons) =>
        val msg = s"Cannot start App. Invalid config: $reasons"
        logger.error(msg)
        throw new IllegalArgumentException(msg)
    }

  // TODO pull this and timeouts into config

  val connectionEndpoint = "connect"

  implicit val system: ActorSystem[User.Command] = ActorSystem(User.behavior(config.httpPort, connectionEndpoint), "app")
  implicit val executor: ExecutionContextExecutor = system.executionContext
  implicit val scheduler: Scheduler = system.scheduler

  /**
   * Provides a way to send an external PUT request to this peer to connect to
   * ("/peer/connect") or disconnect from ("/peer/disconnect") another peer.
   *
   * Requests must provide a JSON body of the form
   * {{{
   *   {
   *     "host": "<hostname_to_connect_to_as_string>",
   *     "port": <port_to_connect_to_as_number>
   *   }
   * }}}
   */
  val peer = pathPrefix("peer") {
    put {
      entity(as[Address]) { address =>
        path("connect") {
          logger.info(s"Received external request to connect to $address")

          // TODO clean up all these .seconds and askTimeout timeouts

          system.ref ! User.RequestConnection(address, x => logger.info(x), 10.seconds)
          complete(StatusCodes.OK)
        } ~
          path("disconnect") {
            logger.info(s"Received external request to disconnect from $address")
            system.ref ! User.Disconnect(address)
            complete(StatusCodes.OK)
          }
      }
    }
  }

  /**
   * GET request endpoint which returns a JSON response with the addresses
   * of all connected and disconnected peers.
   */
  val peers = path("peers") {
    get {
      logger.info(s"Received external query for peers")

      implicit val askTimeout: Timeout = 3.seconds

      onComplete(system.ref ? User.GetPeers) {
        case Failure(exception) =>
          complete(exception)

        case Success(peerGroups) =>
          val map = peerGroups.map(group => group.name -> group.addresses.map(_.toString))
          val json = write(map.toMap)
          complete(json)
      }
    }
  }

  /**
   * The internal peer-to-peer connection request endpoint.
   *
   * Requests must contain the `upgrade-to-websocket` attribute and a "port"
   * parameter or they will be rejected.
   */
  val connect = path(connectionEndpoint) {
    extractHost { host =>
      parameters("port") { portStr =>
        val port = portStr.toInt
        val address = Address(host, port)

        logger.info(s"Received connection request from $address")

        extractWebSocketUpgrade { upgrade =>
          logger.debug(s"Attempting to accept incoming connection from $address")

          implicit val askTimeout: Timeout = 3.seconds

          val futureResponse: Future[HttpResponse] =
            system.ref ? { User.AcceptConnection(_, upgrade, address, x => logger.info(x)) }

          onComplete(futureResponse) {
            case Failure(exception) => complete(exception)
            case Success(response) => complete(response)
          }
        }
      }
    }
  }

  /**
   * Send a message to all connected peers.
   *
   * The body of the POST request will be sent via WebSocket connection to all
   * connected peers.
   */
  val broadcast = path("broadcast") {
    post {
      entity(as[String]) { body =>
        logger.info(s"""Broadcasting message "$body" to all connected peers""")
        system.ref ! User.Broadcast(body)
        complete(StatusCodes.OK)
      }
    }
  }

  // TODO endpoint to send a message to only a single peer?

  /**
   * Disconnect from all connected peers.
   */
  val disconnectAll = path("disconnectAll") {

    // TODO somehow combine with "/peer/disconnect" ? (When no body is given?)

    put {
      logger.info(s"""Disconnecting from all connected peers""")
      system.ref ! User.DisconnectAll
      complete(StatusCodes.OK)
    }
  }

  val bindingFuture = Http()
    .newServerAt(config.httpHost, config.httpPort)
    .adaptSettings(_.mapWebsocketSettings(_.withPeriodicKeepAliveMaxIdle(30.seconds)))
    .bind(peer ~ connect ~ broadcast ~ disconnectAll ~ peers)

  logger.info(s"Listening on ${config.httpHost}:${config.httpPort}")

  // connect to all preconfigured peers
  config.peers.split(",").filter(_.nonEmpty).map(_.split(":")).foreach {
    case Array(host, port) =>
      Try(port.toInt) match {
        case Failure(_) =>
          logger.error(s"""Unable to register preconfigured peer "$host:$port" -- port "$port" must be an Int""")

        case Success(value) =>
          val address = Address(host, value)
          logger.info(s"Attempting to connect to preconfigured peer at $address")
          system.ref ! User.RequestConnection(address, x => logger.info(x), 10.seconds)
          complete(StatusCodes.OK)
      }
    case array =>
      logger.error(s"""Unable to parse preconfigured peer "${array.mkString(":")}" as an Address (must follow host:port format)""")
  }

  // TODO add command-line arguments

  StdIn.readLine() // let it run until user presses return

  bindingFuture
    .flatMap(fff => fff.unbind()) // trigger unbinding from the port
    .onComplete { _ =>

      // TODO cleanly shut down here by informing all peers that we're going offline

      logger.info("App terminated by user")
      system.terminate()
    }

}
