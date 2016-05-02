import scala.collection.immutable.Stack

import org.scalatest.Finders
import org.scalatest.FlatSpec

import edu.cmu.cs.oak.core.Interpreter
import edu.cmu.cs.oak.core.OakEngine
import edu.cmu.cs.oak.env.BooleanValue
import edu.cmu.cs.oak.env.DoubleValue
import edu.cmu.cs.oak.env.IntValue
import edu.cmu.cs.oak.env.OakValue
import edu.cmu.cs.oak.env.SimpleEnvironment
import edu.cmu.cs.oak.env.StringValue
import edu.cmu.cs.oak.env.IntValue
import edu.cmu.cs.oak.env.StringValue
import edu.cmu.cs.oak.env.BooleanValue

object OakTest {
  def engine = new OakEngine()
  def env = new SimpleEnvironment(Map[String, OakValue](), new Stack[OakValue](), new Stack[String](), null)
  
}

class OakTest extends FlatSpec {
  
  /*
  "Simple EchoStatement with String" should "be assigned correctly" in {
    val p = OakTest.engine.loadFromScript("<?php echo 'Hallo World!'; ?>")
    assert("\"Hallo World!\"" == Interpreter.execute(p.getStatement, OakTest.env))
  }
  */
  "Simple ExprStatement with VarExpr (double)" should "be assigned correctly" in {
    val p = OakTest.engine.loadFromScript("<?php $i = 13.37; ?>")
    assert(Interpreter.execute(p.getStatement, OakTest.env).lookup("$i") == DoubleValue(13.37))
  }
  
  "Simple ExprStatement with VarExpr (int)" should "be assigned correctly" in {
    val p = OakTest.engine.loadFromScript("<?php $i = 42; ?>")
    assert(Interpreter.execute(p.getStatement, OakTest.env).lookup("$i") == IntValue(42))
  }
  
  "Simple ExprStatement with VarExpr (false)" should "be assigned correctly" in {
    val p = OakTest.engine.loadFromScript("<?php $i = false; ?>")
    assert(Interpreter.execute(p.getStatement, OakTest.env).lookup("$i") == BooleanValue(false))
  }
  
  "Simple ExprStatement with VarExpr (true)" should "be assigned correctly" in {
    val p = OakTest.engine.loadFromScript("<?php $i = true; ?>")
    assert(Interpreter.execute(p.getStatement, OakTest.env).lookup("$i") == BooleanValue(true))
  }
  
  "Simple ExprStatement with VarExpr (String)" should "be assigned correctly" in {
    val p = OakTest.engine.loadFromScript("<?php $i = 'bener'; ?>")
    assert(Interpreter.execute(p.getStatement, OakTest.env).lookup("$i") == StringValue("bener"))
  }
  
  "Simple ExprStatement with VarExpr" should "be evaluated correctly" in {
    val p = OakTest.engine.loadFromScript("<?php $i = 3; $j = $i; ?>")
    assert(Interpreter.execute(p.getStatement, OakTest.env).lookup("$j") == IntValue(3))
  }
  
  "Simple BinaryAndExpr expressions" should "be evaluated correctly" in {
    val p = OakTest.engine.loadFromScript("<?php $b = true; $j = $b && false; ?>")
    assert(Interpreter.execute(p.getStatement, OakTest.env).lookup("$j") == BooleanValue(false))
  }
  
  "Simple BinaryOrExpr expressions" should "be evaluated correctly" in {
    val p = OakTest.engine.loadFromScript("<?php $b = true; $j = $b || false; ?>")
    assert(Interpreter.execute(p.getStatement, OakTest.env).lookup("$j") == BooleanValue(true))
  }
  
  "Simple NotExpr expressions" should "be evaluated correctly" in {
    val p = OakTest.engine.loadFromScript("<?php $b = true; $j = !$b; ?>")
    assert(Interpreter.execute(p.getStatement, OakTest.env).lookup("$j") == BooleanValue(false))
  }
  
  "NumericExpressions PLUS" should "be evaluated correctly" in {
    var p = OakTest.engine.loadFromScript("<?php $i = 1 + 1?>")
    assert(Interpreter.execute(p.getStatement, OakTest.env).lookup("$i") == IntValue(2))
    
    p = OakTest.engine.loadFromScript("<?php $i = 1 + 1.1?>")
    assert(Interpreter.execute(p.getStatement, OakTest.env).lookup("$i") == DoubleValue(2.1))
    
    p = OakTest.engine.loadFromScript("<?php $i = 1.1 + 1.1?>")
    assert(Interpreter.execute(p.getStatement, OakTest.env).lookup("$i") == DoubleValue(2.2))
  }
  
  "NumericExpressions MINUS" should "be evaluated correctly" in {
    var p = OakTest.engine.loadFromScript("<?php $i = 1 - 1?>")
    assert(Interpreter.execute(p.getStatement, OakTest.env).lookup("$i") == IntValue(0))
    
    p = OakTest.engine.loadFromScript("<?php $i = 1 - 0.5?>")
    assert(Interpreter.execute(p.getStatement, OakTest.env).lookup("$i") == DoubleValue(0.5))
    
    p = OakTest.engine.loadFromScript("<?php $i = 1.2 - 0.6?>")
    assert(Interpreter.execute(p.getStatement, OakTest.env).lookup("$i") == DoubleValue(0.6))
  }
  
  "NumericExpressions MUL" should "be evaluated correctly" in {
    var p = OakTest.engine.loadFromScript("<?php $i = 1 * 2?>")
    assert(Interpreter.execute(p.getStatement, OakTest.env).lookup("$i") == IntValue(2))
    
    p = OakTest.engine.loadFromScript("<?php $i = 1 * 2.1?>")
    assert(Interpreter.execute(p.getStatement, OakTest.env).lookup("$i") == DoubleValue(2.1))
    
    p = OakTest.engine.loadFromScript("<?php $i = 1.0 * 2.1?>")
    assert(Interpreter.execute(p.getStatement, OakTest.env).lookup("$i") == DoubleValue(2.1))
  }
  
  "NumericExpressions DIV" should "be evaluated correctly" in {
    var p = OakTest.engine.loadFromScript("<?php $i = 6 / 2?>")
    assert(Interpreter.execute(p.getStatement, OakTest.env).lookup("$i") == IntValue(3))
    
    p = OakTest.engine.loadFromScript("<?php $i = 6 / 2.0?>")
    assert(Interpreter.execute(p.getStatement, OakTest.env).lookup("$i") == IntValue(3))
    
    p = OakTest.engine.loadFromScript("<?php $i = 5.2 / 2.0?>")
    assert(Interpreter.execute(p.getStatement, OakTest.env).lookup("$i") == DoubleValue(2.6))
  }
  
  "NumericExpressions MOD" should "be evaluated correctly" in {
    var p = OakTest.engine.loadFromScript("<?php $i = 6 % 5?>")
    assert(Interpreter.execute(p.getStatement, OakTest.env).lookup("$i") == IntValue(1))
    
    p = OakTest.engine.loadFromScript("<?php $i = 6 % 5.0?>")
    assert(Interpreter.execute(p.getStatement, OakTest.env).lookup("$i") == IntValue(1))
  }

  
  
}