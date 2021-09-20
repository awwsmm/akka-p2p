package org.akkap2p

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.akkap2p.peers.Address
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

trait JSONSupport extends DefaultJsonProtocol with SprayJsonSupport {
  implicit val addressFormat: RootJsonFormat[Address] = jsonFormat2(Address)
}
