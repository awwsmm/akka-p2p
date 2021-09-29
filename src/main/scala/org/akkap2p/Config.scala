package org.akkap2p

import scala.concurrent.duration.FiniteDuration

import com.typesafe.scalalogging.StrictLogging
import pureconfig.ConfigSource

final case class Timeouts(requestConnection: FiniteDuration, acceptConnection: FiniteDuration, getPeers: FiniteDuration)

final case class Config(httpHost: String, httpPort: Int, peers: String, timeouts: Timeouts)

object Config extends StrictLogging {

  def load(): Config = {
    import pureconfig.generic.auto._
    ConfigSource.default.load[Config] match {
      case Right(value) => value
      case Left(reasons) =>
        val msg = s"Cannot start akka-p2p. Invalid config: $reasons"
        logger.error(msg)
        throw new IllegalArgumentException(msg)
    }
  }

}