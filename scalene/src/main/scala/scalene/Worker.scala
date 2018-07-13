package scalene


import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{SelectionKey, Selector, SocketChannel}

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.util.Try
import scala.util.control.NonFatal

import microactor._
import util._

trait ConnectionContext {
  def time: TimeKeeper
}

sealed trait WorkerMessage

sealed trait ServerToWorkerMessage extends WorkerMessage
object ServerToWorkerMessage {
  case class NewConnection(channel: SocketChannel) extends ServerToWorkerMessage
}


private[this] case object Select extends WorkerMessage with NoWakeMessage


case class WorkEnv(
  time: TimeKeeper,
  timer: Timer,
  dispatcher: Dispatcher
)


trait ServerConnectionHandler extends ConnectionHandler

class ServerWorker(
  server: Actor[WorkerToServerMessage],
  handlerFactory: ConnectionContext => ServerConnectionHandler,
  timeKeeper: TimeKeeper,
  idleTimeout: Duration,
  context: Context
) extends Receiver[WorkerMessage](context) with Logging {


  private val selector = Selector.open()

  private val readBuffer = ByteBuffer.allocateDirect(1024 * 128)

  private val writeBuffer = new WriteBufferImpl(1024 * 1024 * 4)

  private val activeConnections = collection.mutable.Map[Long, ConnectionManager]()

  implicit val mdispatcher:Dispatcher = context.dispatcher
  private val timer = new Timer(50)

  val environment = WorkEnv(timeKeeper, timer, context.dispatcher)

  //this is needed because if the worker sends Select to itself it doesn't
  //yield execution to other actors
  private val coSelect = {
    val workerSelf = self
    context.dispatcher.attach(new Receiver[Select.type](_) {
      def receive(m: Select.type) {
        workerSelf.send(Select)
      }
    })
  }




  private var _nextId = 0L
  private def nextId() = {
    _nextId += 1
    _nextId
  }

  context.dispatcher.addWakeLock(new WakeLock {
    def wake(): Unit = {
      selector.wakeup()
    }
  })

  override def onStart() {
    super.onStart()
    self.send(Select)
    def scheduleIdleTimeout(): Unit = timer.schedule(1000){ 
      closeIdleConnections() 
      scheduleIdleTimeout()
    }
    scheduleIdleTimeout()
  }

  def receive(message: WorkerMessage) = message match {
    case Select => {
      select()
      coSelect.send(Select)
    }
    case ServerToWorkerMessage.NewConnection(channel) => {
      val key = channel.register(selector, SelectionKey.OP_READ)
      val handle = new LiveChannelHandle(channel, key, timeKeeper)
      val context = new ConnectionContext {
        def time = timeKeeper
      }
      val manager = new ConnectionManager(nextId(), handlerFactory(context), handle)
      key.attach(manager)
      activeConnections(manager.id) = manager
      manager.onInitialize(environment)
      manager.onConnected()
    }
  }

  private def removeConnection(manager: ConnectionManager, reason: DisconnectReason): Unit = {
    manager.disconnect()
    activeConnections.remove(manager.id)
    manager.onDisconnected(reason)
    server.send(WorkerToServerMessage.ConnectionClosed)
  }

  private def closeIdleConnections(): Unit = {
    val timeoutTime = timeKeeper() - idleTimeout.toMillis
    val toClose = activeConnections.filter{case (_, c) => 
      c.lastActivity < timeoutTime
    }
    toClose.foreach{case (_, c) => 
      removeConnection(c, DisconnectReason.TimedOut)
    }    
    if (!toClose.isEmpty) {
      info(s"""closed ${toClose.size} idle connection${if (toClose.size > 1) "s" else ""}""")
    }
  }

  private def select() {
    selector.select() //need short wait times to register new connections
    timeKeeper.refresh()
    val selectedKeys  = selector.selectedKeys.iterator
    while (selectedKeys.hasNext) {
      val key: SelectionKey = selectedKeys.next
      if (!key.isValid) {
        error("KEY IS INVALID")
      } else if (key.isConnectable) {
        val con = key.attachment.asInstanceOf[ConnectionManager]
        try {
          con.finishConnect()
        } catch {
          case t: Throwable => {
            removeConnection(con, DisconnectReason.ClientConnectFailed(t))
            key.cancel()
          }
        }
      } else {
        writeBuffer.reset()
        if (key.isReadable) {
          readBuffer.clear
          val sc: SocketChannel = key.channel().asInstanceOf[SocketChannel]
          val manager = key.attachment.asInstanceOf[ConnectionManager]
          try {
            val len = sc.read(readBuffer)
            if (len > -1) {
              readBuffer.flip
              val buffer = ReadBuffer(readBuffer, len)
              manager.onRead(buffer, writeBuffer)
            } else {
              removeConnection(manager, DisconnectReason.RemoteClosed)
              key.cancel()
            }
          } catch {
            case t: java.io.IOException => {
              removeConnection(manager, DisconnectReason.RemoteClosed)
              sc.close()
              key.cancel()
            }
            case t: Throwable => {
              warn(s"Unknown Error! : ${t.getClass.getName}: ${t.getMessage}")
              removeConnection(manager, DisconnectReason.Error(t))
              sc.close()
              key.cancel()
            }
          }
        }
        if (key.isValid && key.isWritable) {
          val manager = key.attachment.asInstanceOf[ConnectionManager]
          try {
                manager.onWrite(writeBuffer)
          } catch {
            case e: java.io.IOException => {
              removeConnection(manager, DisconnectReason.Error(e))
            }
          }
        }
      }
      selectedKeys.remove()

    }

  }

}
