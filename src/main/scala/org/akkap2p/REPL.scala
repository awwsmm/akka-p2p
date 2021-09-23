package org.akkap2p

import akka.actor.typed.ActorSystem
import com.typesafe.scalalogging.StrictLogging
import io.github.awwsmm.zepto.Command.{Quit, help}
import io.github.awwsmm.zepto.{Command, Terminal}
import org.akkap2p.peers.{Address, User}

object REPL extends StrictLogging {

  object Commands {

    def connect(implicit system: ActorSystem[User.Command]): Command =
      Command("connect", "connects to a peer", Address.ifValid(_)(Actions.connect))

    def disconnect(implicit system: ActorSystem[User.Command]): Command =
      Command("disconnect", "disconnects from a peer", { maybeAddress =>
        Address.ifValid(maybeAddress) { address =>
          Actions.disconnect(address)
        }
      })

    def logout(implicit system: ActorSystem[User.Command]): Command =
      Command("logout", "disconnects from all peers", {
        _ => Actions.disconnectAll()
      })

    def send(implicit system: ActorSystem[User.Command]): Command =
      Command("send", "send a message to a peer", {
        case s"$maybeAddress $message" => Address.ifValid(maybeAddress)(Actions.send(_, message))
        case _ => logger.error(s"usage: send <host:port> <remaining text to send>")
      })

    def broadcast(implicit system: ActorSystem[User.Command]): Command =
      Command("broadcast", "broadcast a message to all peers", Actions.broadcast)

    def all(implicit system: ActorSystem[User.Command]): Set[Command] =
      Set(connect, disconnect, logout, send, broadcast, Quit)

  }

  def apply()(implicit system: ActorSystem[User.Command]): Terminal =
    Terminal(Commands.all + help(Commands.all), "\nakka-p2p> ")

}
