package com.jdrews.logstation

import java.io.{BufferedWriter, File, FileWriter}
import java.awt.Desktop
import java.net.URI

import akka.actor.Props
import akka.event.Logging
import akka.pattern._

import com.jdrews.logstation.config.{BridgeController, DefaultConfigHolder, GlobalActorSystem}
import com.jdrews.logstation.service.{LogStationServiceActor, ServiceShutdown}
import com.jdrews.logstation.tailer.LogThisFile
import com.jdrews.logstation.webserver.EmbeddedWebapp
import com.typesafe.config.ConfigFactory

import scala.collection.JavaConversions._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.matching.Regex

/**
 * Created by jdrews on 2/21/2015.
 *
 * The starting point of the LogStation
 * holds toe GlobalActorSystem and kicks off new actors
 */
// TODO: move these to their own files to make it a bit cleaner
case class BufferLength (myVal: Int)
case class MaxLogLinesPerLog (myVal: Int)
case class LogStationName (myVal: String)

//TODO: fix the user lockout follow (or disable altogether) -- add button to scroll to bottom (maybe make it hover or something cool like that?)
object LogStation extends App {
    if (!new java.io.File("logstation.conf").exists) {
        makeConfAndShutdown
    }
    val system = GlobalActorSystem.getActorSystem
    val logger = Logging.getLogger(system, getClass)
    sys.addShutdownHook(shutdown)
    val conf = ConfigFactory.parseFile(new File("logstation.conf"))
    val syntaxList = scala.collection.mutable.Map[String, Regex]()
    if (conf.hasPath("logstation.syntax")) {
        //TODO: redo this so LogTailerActor skips LogStationColorizer if it doesn't exist
        val syntaxes = conf.getConfig("logstation.syntax").entrySet()
        //filter through each config of syntax and convert into map
        syntaxes.foreach(syntax => {
            val matchList: java.util.ArrayList[String] = syntax.getValue().unwrapped().asInstanceOf[java.util.ArrayList[String]]
            logger.debug(matchList.toString)
            syntaxList(matchList.get(0)) = matchList.get(1).r
        })
    }
    logger.debug(s"syntaxList: $syntaxList")

    val maxLogLinesPerLog = {
        if (conf.hasPath("logstation.maxLogLinesPerLog")) {
            val maxLogLinesPerLog = conf.getInt("logstation.maxLogLinesPerLog")
            logger.info(s"maxLogLinesPerLog (user set): $maxLogLinesPerLog")
            maxLogLinesPerLog
        } else {
            val maxLogLinesPerLog = 110
            logger.info(s"maxLogLinesPerLog (default): $maxLogLinesPerLog")
            maxLogLinesPerLog
        }
    }

    val logs = conf.getStringList("logstation.logs").toList

    val bufferLength = {
        if (conf.hasPath("logstation.bufferLength")) {
            val bufferLength = conf.getInt("logstation.bufferLength")
            logger.info(s"bufferLength (user set): $bufferLength")
            bufferLength*logs.length
        } else {
            val bufferLength = 11
            logger.info(s"bufferLength (default): $bufferLength")
            bufferLength*logs.length
        }
    }
    logger.info(s"bufferLength based on logs: $bufferLength")

    val webServerPort = {
        if (conf.hasPath("logstation.webServerPort")) {
            val webServerPort = conf.getInt("logstation.webServerPort")
            logger.info(s"webServerPort (user set): $webServerPort")
            webServerPort
        } else {
            val webServerPort = 8884
            logger.info(s"webServerPort (default): $webServerPort")
            webServerPort
        }
    }

    val logStationName = {
        if (conf.hasPath("logstation.logStationName")) {
            val logStationName = conf.getString("logstation.logStationName")
            logger.info(s"logStationName (user set): $logStationName")
            logStationName
        } else {
            val logStationName = ""
            logger.info(s"logStationName (default): $logStationName")
            logStationName
        }
    }

    // Start up the BridgeActor
    private val bridge = BridgeController.getBridgeActor
    bridge ! MaxLogLinesPerLog(maxLogLinesPerLog)
    bridge ! BufferLength(bufferLength)
    bridge ! LogStationName(logStationName)

    // Start up the embedded webapp
    val webServer = new EmbeddedWebapp(webServerPort, "/")
    webServer.start()

    // Fire up the LogStationServiceActor and push it the files to begin tailing
    val logStationServiceActor = system.actorOf(Props[LogStationServiceActor], name = "LogStationServiceActor")
    logStationServiceActor ! syntaxList
    logs.foreach(log => logStationServiceActor ! new LogThisFile(log))

    // Launch a browser with this URL if available
    if(Desktop.isDesktopSupported())
    {
        Desktop.getDesktop().browse(new URI("http://127.0.0.1:" + webServerPort.toString));
    }

    private def shutdown: Unit = {
        logger.info("Shutdown hook caught.")
        webServer.stop

        try {
            Await.result(gracefulStop(logStationServiceActor, 20 seconds, ServiceShutdown), 20 seconds)
        } catch {
            case e: AskTimeoutException ⇒ logger.error("logStationServiceActor didn't stop in time!" + e.toString)
        }

        //        try {
        //            Await.result(gracefulStop(logStationWebServer, 20 seconds, ServiceShutdown), 20 seconds)
        //        } catch {
        //            case e: AskTimeoutException ⇒ logger.error("logStationWebServer didn't stop in time!" + e.toString)
        //        }

        system.shutdown()
        system.awaitTermination()
        logger.info("Done shutting down.")
    }

    private def makeConfAndShutdown: Unit = {
        println("Welcome to logstation! Creating default logstation.conf...")
        val file = new File("logstation.conf")
        val bw = new BufferedWriter(new FileWriter(file))
        bw.write(DefaultConfigHolder.defaultConfig)
        bw.close()
        println("Please setup your logstation.conf located here: " + file.getAbsolutePath())
        println("Then relaunch and logstation will be located at: http://127.0.0.1:" + webServerPort)
        System.exit(0)
    }
}
