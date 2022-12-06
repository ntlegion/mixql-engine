package org.mixql.engine.core

import com.github.nscala_time.time.Imports.{DateTime, richReadableInstant, richReadableInterval}
import org.zeromq.{SocketType, ZMQ}
import org.mixql.protobuf.ProtoBufConverter

import scala.concurrent.{Await, Future}
import scala.language.postfixOps

object Module {
  var wasCloseExecuted: Boolean = false
}

class Module(executor: IModuleExecutor, indentity: String, host: String, port: Int) {

  import Module._

  var ctx: ZMQ.Context = null
  var server: ZMQ.Socket = null
  var poller: ZMQ.Poller = null

  val heartBeatInterval: Long = 3000
  var processStart: DateTime = null
  var liveness: Int = 3


  def startServer(): Unit = {
    println(s"Module $indentity: Starting main client")


    println(s"Module $indentity: host of server is " + host + " and port is " + port.toString)

    try {
      ctx = ZMQ.context(1)
      server = ctx.socket(SocketType.DEALER)
      //set identity to our socket, if it would not be set,
      // then it would be generated by ROUTER socket in broker object on server

      server.setIdentity(indentity.getBytes)
      println(s"Module $indentity: connected: " + server.connect(s"tcp://$host:${port.toString}"))
      println(s"Module $indentity: Connection established.")

      println(s"Module $indentity:Setting processStart for timer")
      //Set timer
      processStart = DateTime.now()

      println(s"Module $indentity:Setting poller")
      poller = ctx.poller(1)
      println(s"Module $indentity:Register pollin in poller")
      val pollInIndex = poller.register(server, ZMQ.Poller.POLLIN)

      println(s"Module $indentity: Sending READY message to server's broker")
      sendMsgToServerBroker("READY")

      while (true) {
        val rc = poller.poll(heartBeatInterval)
        //        if (rc == 1) throw BrakeException()
        if (poller.pollin(pollInIndex)) {
          println("Setting processStart for timer, as message was received")
          val (clientAdrress, msg, pongHeartBeatMsg) = readMsgFromServerBroker()
          pongHeartBeatMsg match {
            case Some(_) => //got pong heart beat message
              println(s"Module $indentity: got pong heart beat message from broker server")
            case None => //got protobuf message
              implicit val clientAddressStr = new String(clientAdrress)
              executor.reactOnMessage(clientAdrress, msg.get)
          }
          processStart = DateTime.now()
          liveness = 3
        } else {
          val elapsed = (processStart to DateTime.now()).millis
          println(s"Module $indentity: elapsed: " + elapsed)
          liveness = liveness - 1
          if (liveness == 0) {
            println(s"Module $indentity: heartbeat failure, can't reach server's broker. Shutting down")
            throw new BrakeException()
          }
          if (elapsed >= heartBeatInterval) {
            processStart = DateTime.now()
            println(s"Module $indentity: heartbeat work. Sending heart beat. Liveness: " + liveness)
            sendMsgToServerBroker("PING-HEARTBEAT")
          }
        }
      }
    } catch {
      case _: BrakeException => println(s"Module $indentity: BrakeException")
      case ex: Exception =>
        println(s"Module $indentity: Error: " + ex.getMessage)
    } finally {
      close()
    }
    println(s"Module $indentity: Stopped.")
  }

  def close(): Unit = {
    if (!wasCloseExecuted) {
      wasCloseExecuted = true
      if (server != null) {
        println(s"Module $indentity: finally close server")
        server.close()
      }

      if (poller != null) {
        println(s"Module $indentity: finally close poller")
        poller.close()
      }

      try {
        if (ctx != null) {
          println(s"Module $indentity: finally close context")
          implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
          Await.result(Future {
            ctx.term()
          }, scala.concurrent.duration.Duration(5000, "millis"))
        }
      } catch {
        case _: Throwable => println(s"Module $indentity: tiemout of closing context exceeded:(")
      }
    }
  }

  def sendMsgToServerBroker(clientAdrress: Array[Byte], msg: Array[Byte]): Boolean = {
    //Sending multipart message
    println(s"Module $indentity: sendMsgToServerBroker: sending empty frame")
    server.send("".getBytes(), ZMQ.SNDMORE) //Send empty frame
    println(s"Module $indentity: sendMsgToServerBroker: sending clientaddress")
    server.send(clientAdrress, ZMQ.SNDMORE) //First send address frame
    println(s"Module $indentity: sendMsgToServerBroker: sending empty frame")
    server.send("".getBytes(), ZMQ.SNDMORE) //Send empty frame
    println(s"Module $indentity: sendMsgToServerBroker: sending message")
    server.send(msg)
  }

  def sendMsgToServerBroker(msg: String): Boolean = {
    println(s"Module $indentity: sendMsgToServerBroker: convert msg of type String to Array of bytes")
    println(s"Module $indentity: sending empty frame")
    server.send("".getBytes(), ZMQ.SNDMORE) //Send empty frame
    println(s"Module $indentity: Send msg to server ")
    server.send(msg.getBytes())
  }

  def sendMsgToServerBroker(clientAdrress: Array[Byte], msg: scalapb.GeneratedMessage): Boolean = {
    println(s"Module $indentity: sendMsgToServerBroker: convert msg of type Protobuf to Array of bytes")
    sendMsgToServerBroker(clientAdrress, ProtoBufConverter.toArray(msg).get)
  }

  def readMsgFromServerBroker(): (Array[Byte], Option[Array[Byte]], Option[String]) = {
    //FOR PROTOCOL SEE BOOK OReilly ZeroMQ Messaging for any applications 2013 ~page 100
    //From server broker messanger we get msg with such body:
    //indentity frame
    // empty frame --> delimiter
    // data ->
    if (server.recv(0) == null)
      throw new BrakeException() //empty frame
    println(s"$indentity readMsgFromServerBroker: received empty frame")

    val clientAdrress = server.recv(0) //Indentity of client object on server
    // or pong-heartbeat from broker
    if (clientAdrress == null)
      throw new BrakeException()

    var msg: Option[Array[Byte]] = None

    var pongHeartMessage: Option[String] = Some(new String(clientAdrress))
    if (pongHeartMessage.get != "PONG-HEARTBEAT") {
      pongHeartMessage = None

      println(s"$indentity readMsgFromServerBroker: got client address: " + new String(clientAdrress))

      if (server.recv(0) == null)
        throw new BrakeException() //empty frame
      println(s"$indentity readMsgFromServerBroker: received empty frame")

      println(s"Module $indentity: have received message from server ${new String(clientAdrress)}")
      msg = Some(server.recv(0))
    }

    (clientAdrress, msg, pongHeartMessage)
  }
}
