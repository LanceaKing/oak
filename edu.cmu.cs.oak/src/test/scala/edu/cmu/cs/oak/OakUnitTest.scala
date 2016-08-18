package edu.cmu.cs.oak

import java.io.{File, PrintWriter}
import java.nio.file.{Path, Paths}

import edu.cmu.cs.oak.core.{ControlCode, OakEngine, OakInterpreter}
import edu.cmu.cs.oak.env.Environment
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

/**
 * This test class contains unit tests based on PHP snippets and its
 * corresponding expected behavior. We test expressions and statements
 * for basic code coverage.
 */

@RunWith(classOf[JUnitRunner]) //optional
object OakUnitTest extends App {

  // engine and interpreter instance for testing
  val engine = new OakEngine()
  val interpreter = new OakInterpreter()

  //val pp = new PrettyPrinter(200, 0)

  /**
   * Read a PHP source code from file, parses & executes it.
   *
   * @param script PHP source code file
   * @return (ControlCode, Environment)
   */
  def loadAndExecute(path: Path): (ControlCode.Value, Environment) = {
    return interpreter.execute(path)
  }

  /* utility method */
  def url(fileName: String): Path = {
    Paths.get(getClass.getResource("/" + fileName).getPath)
  }

  
  val env = loadAndExecute(url("wordpress/wp-admin/index.php"))
  //val after = Instant.now()
  //println("Symbolic execution successful, duration: " + Duration.between(before, after).toString())

  val pw = new PrintWriter(new File("/home/stefan/git/oak/edu.cmu.cs.oak/out/output.xml"))
  pw.write(env._2.getOutputAsPrettyXML())
  pw.close
  
//  val pw = new PrintWriter(new File("/home/stefan/git/oak/edu.cmu.cs.oak/out/output.xml"))
//  pw.write(env._2.getOutputAsPrettyXML())
//  pw.close
}
