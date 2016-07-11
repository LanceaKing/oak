package edu.cmu.cs.oak.lib.builtin

import java.nio.file.Path

import com.caucho.quercus.expr.Expr

import edu.cmu.cs.oak.lib.InterpreterPluginProvider
import edu.cmu.cs.oak.core.OakInterpreter
import edu.cmu.cs.oak.env.Environment
import edu.cmu.cs.oak.lib.InterpreterPlugin
import edu.cmu.cs.oak.value.OakValue
import edu.cmu.cs.oak.value.NullValue

class Define extends InterpreterPlugin {
  
  override def getName(): String = "define"

  override def visit(provider: InterpreterPluginProvider, args: List[Expr], loc: Path, env: Environment): OakValue = {

    val interpreter = provider.asInstanceOf[OakInterpreter]

    /* Assert that the function has two arguments */
    assert(args.size == 2)

    val constantIdentifier = interpreter.evaluate(args(0), env)
    val constantValue = interpreter.evaluate(args(1), env)
    
    //assert(constantValue != null && (!constantValue.isInstanceOf[NullValue]),  constantIdentifier.toString + args(1))
    
    env.defineConstant( constantIdentifier.toString , constantValue )
    
    null 
  }
  
}