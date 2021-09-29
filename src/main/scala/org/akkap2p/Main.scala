package org.akkap2p

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.server.Directives
import com.typesafe.scalalogging.StrictLogging
import org.akkap2p.actors.User
import org.akkap2p.model.{Address, AddressedMessage}

object Main extends Directives with StrictLogging {
  def main(args: Array[String]): Unit = {

    // load the app configuration from application.conf
    implicit val config: Config = Config.load()

    // what should the REPL prompt look like?
    val prompt = "\nakka-p2p> "

    // what do we do when we receive a message from a peer?
    def onReceive(addressedMessage: AddressedMessage): Unit = {
      val AddressedMessage(address, message) = addressedMessage
      print(s"""$address: "$message"\n$prompt""")
    }

    // the app runs on an Akka ActorSystem
    implicit val system: ActorSystem[User.Command] = ActorSystem(User.behavior, "app")

    // start the server with a defined API
    val server = Server.start(API(onReceive).all)

    // connect to all preconfigured peers
    config.peers.split(",").filter(_.nonEmpty).flatMap(Address.fromString).foreach(Actions.connect(_, onReceive))

    // run the Terminal (this blocks until the user `quit`s)
    Terminal(prompt, onReceive).run()
    Actions.disconnectAll()

    // When the Terminal is stopped, disconnect everything
    Server.stop(server).onComplete(_ => system.terminate())(system.executionContext)
  }
}
