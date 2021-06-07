package org.akkap2p

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import peers.{Address, AddressedMessage}
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

trait JSONSupport extends DefaultJsonProtocol with SprayJsonSupport {
  implicit val addressFormat: RootJsonFormat[Address] = jsonFormat2(Address)
  implicit val addressedMessageFormat: RootJsonFormat[AddressedMessage] = jsonFormat2(AddressedMessage)
}
