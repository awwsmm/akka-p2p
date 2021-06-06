package org.akkap2p

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import peers.Address
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

trait JSONSupport extends DefaultJsonProtocol with SprayJsonSupport {
  implicit val peerUriFormat: RootJsonFormat[Address] = jsonFormat2(Address)
}
