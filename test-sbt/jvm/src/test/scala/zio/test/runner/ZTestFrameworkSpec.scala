/*
 * Copyright 2019 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.test.runner

import java.util.concurrent.atomic.AtomicReference

import sbt.testing._
import zio.test.runner.TestingSupport._
import zio.test.{ DefaultRunnableSpec, Predicate, TestAspect }

import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal
import scala.util.{ Failure, Try }

object ZTestFrameworkSpec {

  def main(args: Array[String]): Unit =
    run(
      test("should return correct fingerprints")(testFingerprints()),
      test("should report events")(testReportEvents()),
      test("should log messages")(testLogMessages())
    )

  def testFingerprints() = {
    val fingerprints = new ZTestFramework().fingerprints.toSeq
    assertEquals("fingerprints", fingerprints, Seq(RunnableSpecFingerprint))
  }

  def testReportEvents() = {
    val reported = ArrayBuffer[Event]()
    loadAndExecute(failingSpecFQN, reported.append(_))

    val expected = Set(
      sbtEvent(failingSpecFQN, "failing test", Status.Failure),
      sbtEvent(failingSpecFQN, "passing test", Status.Success),
      sbtEvent(failingSpecFQN, "ignored test", Status.Ignored)
    )

    assertEquals("reported events", reported.toSet, expected)
  }

  private def sbtEvent(fqn: String, label: String, status: Status) =
    ZTestEvent(fqn, new TestSelector(label), status, None, 0, RunnableSpecFingerprint)

  def testLogMessages() = {
    val loggers = Seq.fill(3)(new MockLogger)

    loadAndExecute(failingSpecFQN, loggers = loggers)

    loggers.map(_.messages) foreach (
      messages =>
        assertEquals(
          "logged messages",
          messages.toList,
          List(
            s"info: ${red("- some suite")}",
            s"info:   ${red("- failing test")}",
            s"info:     ${blue("1")} did not satisfy ${cyan("equals(2)")}",
            s"info:   ${green("+")} passing test"
          )
        )
      )
  }

  private def loadAndExecute(fqn: String, eventHandler: EventHandler = _ => (), loggers: Seq[Logger] = Nil) = {
    val taskDef = new TaskDef(fqn, RunnableSpecFingerprint, false, Array())
    val task = new ZTestFramework()
      .runner(Array(), Array(), getClass.getClassLoader)
      .tasks(Array(taskDef))
      .head

    task.execute(eventHandler, loggers.toArray)
  }

  lazy val failingSpecFQN = SimpleFailingSpec.getClass.getName
  object SimpleFailingSpec
      extends DefaultRunnableSpec(
        zio.test.suite("some suite")(
          zio.test.test("failing test") {
            zio.test.assert(1, Predicate.equals(2))
          },
          zio.test.test("passing test") {
            zio.test.assert(1, Predicate.equals(1))
          },
          zio.test.test("ignored test") {
            zio.test.assert(1, Predicate.equals(2))
          } @@ TestAspect.ignore
        )
      )

  class MockLogger extends Logger {
    private val logged = new AtomicReference(Vector.empty[String])
    private def log(str: String) = {
      logged.getAndUpdate(_ :+ str)
      ()
    }
    def messages: Seq[String] = logged.get()

    override def ansiCodesSupported(): Boolean = false
    override def error(msg: String): Unit      = log(s"error: $msg")
    override def warn(msg: String): Unit       = log(s"warn: $msg")
    override def info(msg: String): Unit       = log(s"info: $msg")
    override def debug(msg: String): Unit      = log(s"debug: $msg")
    override def trace(t: Throwable): Unit     = log(s"trace: $t")
  }

}

object TestingSupport {
  def test[T](l: String)(body: => Unit): Try[Unit] =
    Try {
      body
      println(s"${green("+")} $l")
    }.recoverWith {
      case NonFatal(e) =>
        println(s"${red("-")} $l: ${e.getMessage}")
        e.printStackTrace()
        Failure(e)
    }

  def run(tests: Try[Unit]*) = {
    val failed       = tests.count(_.isFailure)
    val successful   = tests.count(_.isSuccess)
    val failedCount  = if (failed > 0) red(s"failed: $failed") else s"failed: $failed"
    val successCount = if (successful > 0) green(s"successful: $successful") else s"successful: $successful"
    println(s"Summary: $failedCount, $successCount")
    if (failed > 0)
      throw new AssertionError(s"$failed tests failed")
  }

  def assertEquals(what: String, actual: => Any, expected: Any) =
    assert(actual == expected, s"$what:\n  expected: `$expected`\n  actual  : `$actual`")

  def colored(code: String)(str: String) = s"$code$str${Console.RESET}"
  lazy val red                           = colored(Console.RED) _
  lazy val green                         = colored(Console.GREEN) _
  lazy val cyan                          = colored(Console.CYAN) _
  lazy val blue                          = colored(Console.BLUE) _
}