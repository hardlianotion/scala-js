/*
 * Scala.js (https://www.scala-js.org/)
 *
 * Copyright EPFL.
 *
 * Licensed under Apache License 2.0
 * (https://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package org.scalajs.junit

import java.io.ByteArrayOutputStream

import org.junit._
import sbt.testing._

import scala.util.matching.Regex

private[junit] final class JUnitExecuteTest(task: JUnitTask,
    runSettings: RunSettings, bootstrapper: Bootstrapper,
    richLogger: RichLogger, eventHandler: EventHandler) {

  def executeTests(): Unit = {
    val assumptionViolated = try {
      bootstrapper.beforeClass()
      false
    } catch {
      case _: AssumptionViolatedException | _:internal.AssumptionViolatedException =>
        true
    }

    if (assumptionViolated) {
      richLogger.logTestInfo(_.info, "ignored")
      task.ignored += 1
      emitClassEvent(Status.Skipped)
    } else {
      def runWithOrWithoutQuietMode[T](block: => T): T = {
        if (runSettings.quiet) {
          scala.Console.withOut(new ByteArrayOutputStream()) {
            block
          }
        } else {
          block
        }
      }

      runWithOrWithoutQuietMode {
        for (method <- bootstrapper.tests) {
          if (method.ignored) {
            richLogger.logTestInfo(_.info, method.name, "ignored")
            task.ignored += 1
            emitMethodEvent(method.name, Status.Skipped)
          } else {
            executeTestMethod(bootstrapper, method)
          }
        }
      }

      bootstrapper.afterClass()
    }
  }

  private[this] def executeTestMethod(bootstrapper: Bootstrapper,
      test: TestMetadata) = {
    val methodName = test.name

    richLogger.logTestInfo(richLogger.infoOrDebug, methodName, "started")

    val t0 = System.nanoTime
    def getTimeInSeconds(): Double = (System.nanoTime - t0).toDouble / 1000000000

    def handleExpected(expectedException: Class[_ <: Throwable])(body: => Unit) = {
      val wantException = expectedException != classOf[org.junit.Test.None]
      val succeeded = try {
        body
        true
      } catch {
        case t if expectedException.isInstance(t) => false

        case t if wantException =>
          val expName = expectedException.getName
          val gotName = t.getClass.getName
          throw new Exception(
              s"Unexpected exception, expected<$expName> but was<$gotName>", t)
      }

      if (succeeded && wantException)
        throw new AssertionError("Expected exception: " + expectedException.getName)
    }

    var exceptions: List[Throwable] = Nil
    try {
      val instance = bootstrapper.newInstance()
      try {
        bootstrapper.before(instance)
        handleExpected(test.annotation.expected) {
          bootstrapper.invokeTest(instance, test.name)
        }
      } catch {
        case t: Throwable => exceptions ::= t
      } finally {
        bootstrapper.after(instance)
      }
    } catch {
      case t: Throwable => exceptions ::= t
    }

    val timeInSeconds = getTimeInSeconds()

    exceptions.reverse match {
      case Nil =>

      case e :: Nil if isAssumptionViolation(e) =>
        richLogger.logTestException(_.warn, "Test assumption in test ", methodName, e, timeInSeconds)
        emitMethodEvent(methodName, Status.Skipped)

      case e :: es =>
        def emit(t: Throwable) = {
          richLogger.logTestException(_.error, "Test ", methodName, t, timeInSeconds)
          richLogger.trace(t)
          task.failed += 1
        }

        emit(e)
        emitMethodEvent(methodName, Status.Failure)
        es.foreach(emit)
    }

    richLogger.logTestInfo(_.debug, methodName,
        s"finished, took ${getTimeInSeconds()} sec")

    // Scala.js-specific: timeouts are warnings only, after the fact
    val timeout = test.annotation.timeout
    if (timeout != 0 && timeout <= timeInSeconds) {
      richLogger.log(_.warn, "Timeout: took " + timeInSeconds + " sec, expected " +
          (timeout.toDouble / 1000) + " sec")
    }

    if (exceptions.isEmpty)
      emitMethodEvent(methodName, Status.Success)

    task.total += 1
  }


  private def emitClassEvent(status: Status): Unit = {
    val selector = new TestSelector(task.taskDef.fullyQualifiedName)
    eventHandler.handle(new JUnitEvent(task.taskDef, status, selector))
  }

  private def emitMethodEvent(methodName: String, status: Status): Unit = {
    val selector = new TestSelector(task.taskDef.fullyQualifiedName + "." + runSettings.decodeName(methodName))
    eventHandler.handle(new JUnitEvent(task.taskDef, status, selector))
  }

  private def isAssumptionViolation(ex: Throwable): Boolean = {
    ex.isInstanceOf[AssumptionViolatedException] ||
    ex.isInstanceOf[internal.AssumptionViolatedException]
  }
}
