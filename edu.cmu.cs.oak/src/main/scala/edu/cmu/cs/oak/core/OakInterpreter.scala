package edu.cmu.cs.oak.core

import java.nio.file.Path
import java.nio.file.Paths

import scala.collection.JavaConversions.mapAsJavaMap
import scala.collection.JavaConversions.mapAsScalaMap
import scala.collection.JavaConversions.setAsJavaSet
import scala.collection.immutable.Stack
import scala.collection.mutable.AnyRefMap
import scala.collection.mutable.ListBuffer

import org.slf4j.LoggerFactory

import com.caucho.quercus.expr._
import com.caucho.quercus.program.QuercusProgram
import com.caucho.quercus.statement._

import edu.cmu.cs.oak.env.BranchEnv
import edu.cmu.cs.oak.env.Call
import edu.cmu.cs.oak.env.ClassDef
import edu.cmu.cs.oak.env.Constraint
import edu.cmu.cs.oak.env.Environment
import edu.cmu.cs.oak.env.FunctionDef
import edu.cmu.cs.oak.env.OakHeap
import edu.cmu.cs.oak.exceptions.VariableNotFoundException

import edu.cmu.cs.oak.nodes.DNode
import edu.cmu.cs.oak.value._
import java.io.PrintWriter
import java.io.File
import edu.cmu.cs.oak.lib.InterpreterPluginProvider

class OakInterpreter extends InterpreterPluginProvider with CallRecorder {

  loadPlugins()

  // Logger for the interpreter instance
  val logger = LoggerFactory.getLogger(classOf[OakInterpreter])

  /**
   *  Path to the *currently* executed source file. This path is cached and changed
   *  while including references source files.
   */
  var path: Path = null

  /**
   * Entry point of the program, i.e., the
   *  project's root folder.
   */
  var rootPath: Path = null

  /**
   * (Mutable) include stack (similar to a call stack) to keep track
   * of recent includes in order to avoid recursion and manage
   * "require_once" statements.
   */
  var includes = Stack[Path]()

  val engine = new OakEngine()

  def execute(path: Path): (ControlCode.Value, Environment) = {

    // The current script is located at this.pathi
    this.path = path

    // The root directory is path.getParent()
    this.rootPath = path.getParent()

    val program = engine.loadFromFile(path)
    var env = new Environment(null, Stack[Call](), Constraint("true"))

    // Make sure, the OakHeap is cleared
    OakHeap.clear()

    /**
     * Initialize the environment by adding context variables and loading some
     * required libraries.
     */
    //    try {
    //      env.update("$_POST", SymbolValue("$_POST", OakHeap.getIndex, SymbolFlag.BUILTIN_VALUE))
    //      env.update("$_GET", SymbolValue("$_GET", OakHeap.getIndex, SymbolFlag.BUILTIN_VALUE))
    //      env.addToGlobal("$_SERVER")
    //      env.update("$_SERVER", SymbolValue("$_SERVER", OakHeap.getIndex, SymbolFlag.BUILTIN_VALUE))
    //      execute(engine.loadFromFile(Paths.get(getClass.getResource("/pear/PEAR.php").toURI())), env)
    //      execute(engine.loadFromFile(Paths.get(getClass.getResource("/Exception.php").toURI())), env)
    //      execute(engine.loadFromFile(Paths.get(getClass.getResource("/COM.php").toURI())), env)
    //      execute(engine.loadFromFile(Paths.get(getClass.getResource("/php_user_filter.php").toURI())), env)
    //      execute(engine.loadFromFile(Paths.get(getClass.getResource("/stdClass.php").toURI())), env)
    //    } catch {
    //      case _: Throwable => throw new RuntimeException("Error initializing PHP environment.")
    //    }

    // Execute the parsed program
    execute(program, env)

    serializeSymbolicCalls()
    serializeUndefinedCalls()

    (ControlCode.OK, env)
  }

  def execute(program: QuercusProgram, env: Environment): ControlCode.Value = {
    defineFunctions(program, env)
    execute(program.getStatement, env)
    return ControlCode.OK
  }

  /*
   * Statement of the form 
   * <Statement>; <Statement>; ...
   */
  private def executeBlockStatement(stmt: BlockStatement, env: Environment): ControlCode.Value = {
    stmt.getStatements.takeWhile(!_.isInstanceOf[BreakStatement] && !env.hasTerminated()).foreach(s => execute(s, env))
    ControlCode.OK
  }

  /*
   * Statement of the form
   * echo <Expr>;
   */
  private def executeEchoStatement(stmt: EchoStatement, env: Environment): ControlCode.Value = {
    val expr = stmt._expr

    expr match {
      case aget: ArrayGetExpr => {
        val get_array_name = (e: String) => ("\\](\\s|\\d|[^(\\[|\\])])*\\[".r replaceAllIn (e.toString.reverse, "")).reverse
        val aget_name = get_array_name(aget.toString)
        
        val get_array_indices = (e: String) => e.split("\\[").tail.map(s => s.slice(0, s.size - 1).replaceAll("\"", "")).toList
        
        
        // FIXME re-use! this is a copy
        val array_indices = get_array_indices(aget.toString).map {
          i =>
            {
              if (i.startsWith("$")) {
                env.lookup(i) match {
                  case sv: StringValue => {

                    /* Since this StringValue is used by the ArrayValue internally, 
                      * we omit the location information. */
                    sv.setLocation(null)
                    sv
                  }
                  case ov: OakValue => ov
                }
              } else {
                try {
                  IntValue(i.toLong)
                } catch {
                  case e: Exception => StringValue(i, "", 0)
                }
              }
            }
        }
        
        val array_get_with_possible_choice = Choice.arrayLookup(env.lookup(aget_name), array_indices, env)
        env.addOutput(DNode.createDNode(array_get_with_possible_choice, stmt._location))
        
      }
      case _ => {
        val value = evaluate(expr, env)
        val valueX = value match {
          case sv: StringValue => sv
          case ov: OakVariable => env.extract(ov)
          case a: ArrayValue => StringValue("[..][" + a.array.size + "]", "", 0) //throw new RuntimeException("Can't echo an entire array (" + expr + ").")
          case _ => value
        }
        env.addOutput(DNode.createDNode(valueX, stmt._location))
      }
    }

    return ControlCode.OK
  }

  /*
   * Statement of the form
   * <Var> = <Expr>;
   */
  private def executeExprStatement(s: ExprStatement, env: Environment): ControlCode.Value = {

    val e = s._expr

    e match {
      case exit: FunExitExpr => {
        ControlCode.OK
      }
      case u: VarUnsetExpr => {
        val varToUnset = u._var
        varToUnset match {
          case varex: VarExpr => {
            env.unset(varToUnset.toString)
          }
          case ag: ArrayGetExpr => {
            val expr = ag._expr
            /*if (!env.lookup(expr.toString).isInstanceOf[SymbolValue]) {
              val index = evaluate(ag._index, env)._1
              //env.unsetArrayElement(expr.toString, index)
            }*/
          }
          case ofe: ObjectFieldExpr => {
            evaluate(ofe, env)
          }
        }
        ControlCode.OK
      }

      case bo: BinaryOrExpr => {
        if (bo._right.isInstanceOf[FunDieExpr]) {
          evaluate(bo._right, env)
          evaluate(bo._left, env)
        } else {

        }
        ControlCode.OK
      }

      /**
       * Assignment $a = <Expr>;
       */
      case b: BinaryAssignExpr => {
        val value = evaluate(b, env)
        if (value.isInstanceOf[StringValue]) {
          val svalue = value.asInstanceOf[StringValue]
          svalue.setLocation(s._location)
        }
        ControlCode.OK
      }

      case c: CallExpr => {
        val result = evaluate(c, env)
        //assert(!(result._2.getOutput() eq env.output))
        //env.addOutput( result._2.getOutput() )
        return ControlCode.OK
      }

      /* Assignment of a reference, i.e., something like $var = &$value; */
      case e: BinaryAssignRefExpr => {

        /* Retrieve the var and value value */
        val name = e._var
        val value = e._value

        /* Update the environment so that 
         * + $var is mapped to the same reference as evaluate($value)
         * */
        val oakVariable = value match {
          case varexpr: VarExpr => {

            /* Look up the name of the referenced variable in the String -> OakVariable map*/
            env.getRef(varexpr.toString)
          }
          case aget: ArrayGetExpr => {

            // Get the reference of the stuff
            val exx = aget._expr
            val expr = evaluate(exx, env)
            val index = evaluate(aget._index, env)

            if (!expr.isInstanceOf[ArrayValue]) {
              return ControlCode.ERROR
            }
            try {
              val ref = expr.asInstanceOf[ArrayValue].getRef(index)
              ref
            } catch {
              case nsee: NoSuchElementException => return ControlCode.ERROR
            }
          }

          /*
           * Since we are inside a BinaryAssignRefExpr, we assume that the returned value 
           * actually is a OakVariable/reference
           */
          case ome: ObjectMethodExpr => {
            evaluate(ome, env) // return value
            val returnref = env.getRef("$returnref")
            val ref = returnref
            ref
          }
          case _ => throw new RuntimeException(value + " (" + value.getClass + ") is unimplemented.")
        }

        /* Update */
        env.setRef(name.toString, oakVariable)
        return ControlCode.OK
      }

      case o: ObjectMethodExpr => {
        evaluate(o, env)
        ControlCode.OK
      }

      case d: FunDieExpr => {
        evaluate(d, env)
        ControlCode.OK
      }

      case include: FunIncludeExpr => {
        evaluate(include, env)
        ControlCode.OK
      }

      case includeOnce: FunIncludeOnceExpr => {
        evaluate(includeOnce, env)
        ControlCode.OK
      }

      case e: AbstractBinaryExpr => {
        ControlCode.OK
      }

      case p: UnaryPostIncrementExpr => {
        val e = p._expr
        val i = p._incr
        val result = evaluate(e, env) match {
          case a: SymbolicValue => SymbolValue(p.toString, OakHeap.getIndex, SymbolFlag.AMBIGUOUS_VALUE)
          case b: NumericValue => {
            b + IntValue(i)
          }
          case _ => SymbolValue(p.toString, OakHeap.getIndex, SymbolFlag.AMBIGUOUS_VALUE)
        }
        env.update(e.toString, result)
        ControlCode.OK
      }

      /**
       * Assignment of the form
       * list($a, $b, ...) = array(1, 2, ...)
       */
      case e: BinaryAssignListExpr => {

        // value to assign, needs to be an array
        val value = e._value

        // list to assign array elements to
        val listHead = e._listHead
        val vars = listHead._varList
        evaluate(value, env) match {
          case a: ArrayValue => {
            assert(a.array.size == vars.size)
            (vars zipWithIndex).foreach {
              case (v, av) => env.update(v.toString, env.extract(a.array.get(IntValue(av)).get))
            }
          }
          case s: SymbolicValue => {
            if (vars != null) {
              vars.foreach {
                v => env.update(if (v != null) v.toString else "", SymbolValue(if (v != null) v.toString else "", OakHeap.getIndex, SymbolFlag.AMBIGUOUS_VALUE))
              }
            }
          }
          case s: OakValue => {
            vars.filter { v => v != null }.foreach {
              v => env.update(v.toString(), s)
            }
          }
        }
        ControlCode.OK
      }

      case cme: ClassMethodExpr => {
        evaluate(cme, env)
        ControlCode.OK
      }
      case e: UnarySuppressErrorExpr => {
        evaluate(e, env)
        ControlCode.OK
      }

      case e: ConditionalExpr => {
        evaluate(e, env)
        ControlCode.OK
      }
      case e: UnaryPreIncrementExpr => {
        evaluateUnaryPreIncrementExpr(e, env)
        ControlCode.OK
      }

      case _ => throw new RuntimeException(e.getClass + " is not implemented for ExprStatement, expr is " + e.toString())
    }
  }

  /*
   * Statements of the form
   * if (<Expr>) {
   *    <Statement>
   * } else {
   *    <Statement>
   * }
   */
  private def executeIfStatement(s: IfStatement, env: Environment): ControlCode.Value = {
    /* Retrieve the condition and both statements 
     * from the IfStatement AST node via reflection. */
    val condition = Constraint(s._test.toString())
    val test = evaluate(s._test, env)
    val trueBlock = s._trueBlock
    val falseBlock = s._falseBlock

    /* Execute the statements symbolically by executing both branches and merging them
     * together afterwards. */
    val branches = Environment.fork(env, List(condition))

    /* Execute both branches with the corresponding branch environments. */

    if (test.isInstanceOf[BooleanValue]) {
      val testB = test.asInstanceOf[BooleanValue]
      if (testB.v) {
        execute(trueBlock, branches.head)
        env.weaveDelta(branches.head.getDelta)
      } else {
        try {
          execute(falseBlock, branches.last)
          env.weaveDelta(branches.last.getDelta)
        } catch {
          case e: Throwable => branches.last // ?
        }
      }
    } else { // u.a. symbol(ic) values
      execute(trueBlock, branches.head)
      try {
        execute(falseBlock, branches.last)
      } catch {
        case e: Throwable => {
          env.weaveDelta(branches.head.getDelta)
          return ControlCode.OK
        }
      }
      env.weaveDelta(BranchEnv.join(List(branches.head, branches.last), List(condition)))
    }
    return ControlCode.OK
  }

  /**
   * Statement of the form
   * while (<test>) {
   * 	 <block>
   * }
   */
  private def executeWhileStatement(s: WhileStatement, env: Environment): ControlCode.Value = {
    val loopEnv = Environment.createLoopEnvironment(env)
    if (s._test.isInstanceOf[BinaryAssignExpr]) {
      evaluate(s._test, loopEnv)
    }
    execute(s._block, loopEnv)
    env.weaveDelta(loopEnv.getDelta())
    return ControlCode.OK
  }

  /**
   * Statement of the form
   * return <-Expr>;
   */
  private def executeReturnStatement(s: ReturnStatement, env: Environment): ControlCode.Value = {
    val expr = s._expr
    val v = try {
      evaluate(expr, env)
    } catch {
      case vnfe: VariableNotFoundException => {
        NullValue("Could not evaluate return value " + s._expr + ".")
      }
    }
    env.update("$return", v)
    env.terminate()
    return ControlCode.OK
  }

  /**
   * Adds a class definition to the environment.
   */
  private def executeClassDefStatement(s: ClassDefStatement, env: Environment): ControlCode.Value = {

    val interpreted = s._cl
    val classDefCasted = interpreted.asInstanceOf[com.caucho.quercus.program.ClassDef]

    val name = interpreted.getName

    if (env.classDefs.keySet contains name) {
      return ControlCode.OK
    }

    val fields = interpreted._fieldMap

    val staticFields = interpreted._staticFieldMap
    val constants = interpreted._constMap

    val methods = interpreted._functionMap

    /* Define all methods as functions */
    val classMethodNames = List(methods.keySet().toArray: _*)

    val classMethodDefs = ListBuffer[FunctionDef]()
    classMethodNames.foreach {
      name => classMethodDefs.append(env.defineFunction(methods.get(name)))
    }

    /* Add all methods ex*/
    var classMethodDefMap = Map[String, FunctionDef]()
    classMethodDefs.foreach {
      fd =>
        {
          if (!fd.name.equals("__construct")) {
            classMethodDefMap = classMethodDefMap + (fd.name -> fd)
          }
        }
    }

    val classFieldNamez = List(fields.keySet().toArray: _*)
    var classFieldNames = classFieldNamez.map { name => name.toString() }

    /* Retrieve all parent classes */
    if ((classDefCasted._parentName != null) && (!(classDefCasted._parentName equals "WP_HTTP_Streams"))) {
      val parentClassDef = try {
        val cd = env.getClassDef(classDefCasted._parentName)
        classFieldNames ++= cd.getFields()
        cd.methods.foreach {
          case (name, fdef) => classMethodDefMap += (name -> fdef)
        }
        cd
      } catch {
        case nsee: NoSuchElementException => null
      }
    }

    val staticFieldz = staticFields.map {
      case (k, v) => (k.toString() -> evaluate(v._value, env))
    }.toMap

    val constantz = constants.map {
      case (k, v) => (k.toString() -> evaluate(v, env))
    }.toMap

    val parent = if (interpreted._parentName != null) interpreted._parentName else ""

    val classDef = ClassDef(name, Map[String, OakValue](), classMethodDefMap, constantz, staticFieldz, parent)

    /* Add construcotrs to the class definition. */
    classMethodDefs.takeWhile {
      fd => fd.name.equals("__construct")
    }.foreach {
      fd => classDef.addConstructor(fd.args.size, fd)
    }

    /* Get all field names */
    val classFieldMap = fields.map {
      case (sv, cf) => (sv.toString() -> evaluate(cf._initValue, env))
    }

    if (!(parent equals "")) {
      try {
        val parentClassDef = env.getClassDef(parent)
        parentClassDef.fields.foreach {
          case (k, v) => classFieldMap.put(k, v)
        }
        parentClassDef.methods.foreach {
          case (k, v) => classMethodDefMap += (k -> v)
        }
      } catch {
        case e: Exception => {
          logger.error("Class " + parent + " could not be found.")
        }
      }
    }
    env.addClass(classDef)
    env.getClassDef(name).initFields(classFieldMap.toMap)
    return ControlCode.OK
  }

  private def executeReturnRefStatement(s: ReturnRefStatement, env: Environment): ControlCode.Value = {

    val u = s._expr
    val returnRef = u match {
      case t: ThisFieldExpr => {
        val fieldName = t._name.toString()
        try {
          val thisObj = env.lookup("$this").asInstanceOf[ObjectValue]
          thisObj.getFieldRef(fieldName) //thisArray.getRef(StringValue(fieldName, t._location.getFileName(), t._location.getLineNumber()))
        } catch {
          case cce: ClassCastException => null
        }

      }
      case v: VarExpr => {
        env.getRef(v.toString)
      }
      case a: ArrayGetExpr => {
        val array = env.lookup(a._expr.toString()).asInstanceOf[ArrayValue]
        val index = evaluate(a._index, env)
        array.getRef(index)
      }
    }
    env.update("$returnref", returnRef)
    env.terminate()
    return ControlCode.OK
  }

  /**
   * Statement of the form:
   *
   * switch ($name) {
   * 	case "Alice":
   * 		echo "Hi, my name is Alice.";
   * 		break;
   * 	case "Bob":
   * 		echo "Hi, my name is Bob."
   * 		break;
   * 	default:
   * 		echo "My name is neither Alice, nor Bob.";
   * 		break;
   * }
   */
  private def executeSwitchStatement(s: SwitchStatement, env: Environment): ControlCode.Value = {

    val v = s._value
    val cases = s._cases.toList.map { m => m(0) }
    val blocks = s._blocks.toList
    val default = if (s._defaultBlock != null) s._defaultBlock else new BlockStatement(null, Array[Statement]())
    val conditions = cases.map { c => Constraint(v + " == " + c) }

    val results = (Environment.fork(env, conditions) zip (default :: blocks.reverse).reverse)
    results.foreach { case (e, s) => execute(s, e) }
    val bResults = results.map { e => e._1 }

    val delta = BranchEnv.join(bResults, conditions)
    env.weaveDelta(delta)
    return ControlCode.OK
  }

  private def executeBreakStatement(s: BreakStatement, env: Environment): ControlCode.Value = {
    ControlCode.OK
  }

  private def executeContinueStatement(s: ContinueStatement, env: Environment): ControlCode.Value = {
    ControlCode.OK
  }

  private def executeForStatement(s: ForStatement, env: Environment): ControlCode.Value = {
    val init = s._init
    val block = s._block

    // Initialize the loop environment
    val loopEnv = Environment.createLoopEnvironment(env)
    evaluate(init, loopEnv)
    execute(block, loopEnv)
    env.weaveDelta(loopEnv.getDelta())
    return ControlCode.OK
  }

  /**
   * These statements
   */
  private def executeTextStatement(s: TextStatement, env: Environment): ControlCode.Value = {

    val value = s._value
    val file = s._location.getFileName
    val line = s._location.getLineNumber
    env.addOutput(DNode.createDNode(StringValue(value.toString(), file, line), s._location))
    ControlCode.OK
  }

  private def executeGlobalStatement(s: GlobalStatement, env: Environment): ControlCode.Value = {
    val value = s._var.toString
    try {
      env.addToGlobal(value)
    } catch {
      case vnfe: VariableNotFoundException => {

      }
    }
    ControlCode.OK
  }

  private def executeStaticStatement(s: StaticStatement, env: Environment): ControlCode.Value = {
    /**
     * protected StringValue _uniqueStaticName;
     *
     * protected VarExpr _var;
     * protected Expr _initValue;
     */
    val uniqueStaticName = s._uniqueStaticName.toString()
    val variable = s._var
    val initValue = evaluate(s._initValue, env)

    try {
      env.addToGlobal(variable.toString)
    } catch {
      case vnfe: VariableNotFoundException => {
        throw new RuntimeException(vnfe)
      }
    }
    env.update(variable.toString, initValue)
    ControlCode.OK
  }

  /**
   * There are two syntaxes for foreach statements,
   * A)
   * foreach (<array_expression> as $value)
   * <statement>
   *
   * or B)
   * foreach (<array_expression> as $key => $value)
   * <statement>
   */
  private def executeForeachStatement(s: ForeachStatement, env: Environment): ControlCode.Value = {
    val objExpr = s._objExpr

    val key = s._key
    val value = s._value
    val block = s._block
    val loopEnv = Environment.createLoopEnvironment(env)

    evaluate(objExpr, env) match {
      case av: ArrayValue => {
        if (av.array.size > 0) {
          loopEnv.update(value.toString(), try {
            av.get(IntValue(0), env)
          } catch {
            case _ => SymbolValue(s.toString, OakHeap.getIndex, SymbolFlag.AMBIGUOUS_VALUE)
          })
        }
      }
      case _ => {
        loopEnv.update(value.toString(), SymbolValue(s.toString, OakHeap.getIndex, SymbolFlag.AMBIGUOUS_VALUE))
      }
    }

    execute(block, loopEnv)
    env.weaveDelta(loopEnv.getDelta())

    ControlCode.OK
  }

  private def executeDoStatement(s: DoStatement, env: Environment): ControlCode.Value = {
    val loopEnv = Environment.createLoopEnvironment(env)
    execute(s._block, loopEnv)
    env.weaveDelta(loopEnv.getDelta())
    return ControlCode.OK
  }

  private def executeFunctionDefStatement(s: FunctionDefStatement, env: Environment): ControlCode.Value = {
    val function = s._fun
    if (!(env.containsFunction(function._name))) {
      env.defineFunction(env.defineFunction(function))
    }
    ControlCode.OK
  }

  private def executeTryStatement(s: TryStatement, env: Environment): ControlCode.Value = {
    execute(s._block, env)
    ControlCode.OK
  }

  private def executeThrowStatement(s: ThrowStatement, env: Environment): ControlCode.Value = {
    evaluate(s._expr, env)
    ControlCode.OK
  }

  // FIXME TODO Make class definitions environment-dependent
  private def executeClassStaticStatement(s: ClassStaticStatement, env: Environment): ControlCode.Value = {
    val staticName = s._staticName.toString()
    val className = staticName.slice(0, staticName.indexOf("::"))
    val fieldName = staticName.slice(staticName.lastIndexOf("::") + 2, staticName.size)
    env.setStaticClassField(className, fieldName, evaluate(s._initValue, env))
    ControlCode.OK
  }

  def execute(stmt: Statement, env: Environment): ControlCode.Value = {
    if (!env.hasTerminated()) {
      stmt match {
        case s: ClassDefStatement => {
          executeClassDefStatement(s, env)
        }
        case s: BlockStatement => {
          executeBlockStatement(s, env)
        }
        case s: EchoStatement => {
          executeEchoStatement(s, env)
        }
        case s: ExprStatement => {
          executeExprStatement(s, env)
        }
        case s: IfStatement => {
          executeIfStatement(s, env)
        }
        case s: WhileStatement => {
          executeWhileStatement(s, env)
        }
        case s: ReturnStatement => {
          executeReturnStatement(s, env)
        }
        case s: ReturnRefStatement => {
          executeReturnRefStatement(s, env)
        }
        case s: SwitchStatement => {
          executeSwitchStatement(s, env)
        }
        case s: BreakStatement => {
          executeBreakStatement(s, env)
        }
        case s: ForStatement => {
          executeForStatement(s, env)
        }
        case s: ContinueStatement => {
          executeContinueStatement(s, env)
        }
        case s: TextStatement => {
          executeTextStatement(s, env)
        }
        case s: GlobalStatement => {
          executeGlobalStatement(s, env)
        }
        case s: StaticStatement => {
          executeStaticStatement(s, env)
        }
        case s: ForeachStatement => {
          executeForeachStatement(s, env)
        }
        case s: DoStatement => {
          executeDoStatement(s, env)
        }
        case s: FunctionDefStatement => {
          executeFunctionDefStatement(s, env)
        }
        case s: TryStatement => {
          executeTryStatement(s, env)
        }
        case s: ThrowStatement => {
          executeThrowStatement(s, env)
        }
        case s: ClassStaticStatement => {
          executeClassStaticStatement(s, env)
        }
        case _ => throw new RuntimeException(stmt + " unimplemented.")
      }
    } else {
      //logger.error("Execution suspendedd " + stmt.getClass + " at " + stmt.getLocation.getFileName + ":" + stmt.getLocation.getLineNumber)
      return ControlCode.OK
    }
  }

  private def evaluateLiteralExpr(e: LiteralExpr, env: Environment): OakValue = {
    val value = e._value

    if (e.isBoolean) {
      if (e.isTrue()) {
        return BooleanValue(true)
      } else if (e.isFalse()) {
        return BooleanValue(false)
      } else {
        throw new RuntimeException("E")
      }
    } else if (e.isNumber) { //numeric
      return try {
        if (e.isLong) {
          return IntValue(e.toString.toLong)
        } else if (e.isDouble) {
          return DoubleValue(e.toString.toDouble)
        } else {
          try {
            return IntValue(e.toString.toInt)
          } catch {
            case nfe: NumberFormatException => throw new RuntimeException()
          }
        }
      } catch {
        case e: NumberFormatException => throw new RuntimeException(e)
      }
    } else {
      throw new RuntimeException("C")
    }
  }

  private def evaluateLiteralUnicodeExpr(e: LiteralUnicodeExpr, env: Environment): OakValue = {
    val sv = StringValue(e.toString.slice(1, e.toString.length - 1), e._location.getFileName(), e._location.getLineNumber())
    return sv
  }

  private def evaluateVarExpr(e: VarExpr, env: Environment): OakValue = {
    try {
      env.lookup(e.toString)
    } catch {
      case vnfe: VariableNotFoundException => null
    }
  }

  private def evaluateUnaryNotExpr(e: UnaryNotExpr, env: Environment): OakValue = {
    val p = evaluate(e.getExpr, env)
    return p match {

      // default case: e evaluates to a BooleanValue
      case e: BooleanValue => {
        e.not
      }

      // exceptional case: Found symbolic value -> return symbolic value
      case v2: SymbolicValue => SymbolValue(e.toString, OakHeap.getIndex, SymbolFlag.AMBIGUOUS_VALUE)

      case a: ArrayValue => {
        val b = if (a.array.size == 0) {
          false
        } else {
          true
        }
        BooleanValue(b)
      }

      case _ => SymbolValue(e.toString, OakHeap.getIndex, SymbolFlag.AMBIGUOUS_VALUE)
    }
  }

  /**
   *
   */
  private def evaluateAbstractBinaryExpr(e: AbstractBinaryExpr, env: Environment): OakValue = {
    e match {
      case e: BinaryOrExpr => {
        if (e._right.isInstanceOf[FunDieExpr]) {
          evaluate(e._right, env)
          return evaluate(e._left, env)
        } else {
          return SymbolValue(e.toString, OakHeap.getIndex, SymbolFlag.EXPR_LEFT_UNEVALUATED)
        }
      }
      case _ => SymbolValue(e.toString, OakHeap.getIndex, SymbolFlag.EXPR_LEFT_UNEVALUATED)
    }

  }

  private def evaluateCallExpr(e: CallExpr, env: Environment): OakValue = {

    val name = e._name.toString()

    /* Retrieve the function call arguments. */
    var args = ListBuffer[Expr]()
    e._args.foreach {
      o => args += o
    }

    /* If the function has already been called, i.e., its name
       * is contained in the call stack, we just return a symbol value
       * in order to avoid recursive function calls. */
    if (!(env.getCalls().map(c => c.name) contains name)) {

      /* If the function called refers to one implemented library function, such as
       * count($x) for arrays or concat/. for string literals, that implementation
       * will be used (instead). 
       * Each plugin implements its own library method, this.plugins is a map from 
       * the library function names to the actual plugin.
       * */
      if (getPlugins.contains(name)) {

        /* The library function plugin visits and evaluates the expression. */
        val plugin = getPlugin(name)
        return this.accept(getPlugin(name), args.map(e => evaluate(e, env)).toList, e._location, env)
      }

      val functionCall = Call(name, (Paths.get(e._location.getFileName), e._location.getLineNumber), args.toList.map(e => evaluate(e, env)))

      /* Retrieve the function definition from the environment. If we fail
       * loading the function, we return with a symbol value, i.e., the called function
       * is either undefined or unimplemented. */
      val function = try {
        env.getFunction(name)
      } catch {
        case ex: Exception => {

          // Record function call
          recordUndefinedCall(functionCall)

          return SymbolValue(e.toString, OakHeap.getIndex(), SymbolFlag.FUNCTION_CALL)
        }
      }
      // Assert that the number of arguments in the function call and declaration match
      //assert(function.getArgs.length == args.length)

      /* CALL-BY-VALUE only */

      /**
       * Create a new (function) environment with pre-assigned arguments
       */
      val functionEnv = Environment.createFunctionEnvironment(env, functionCall)
      prepareFunctionOrMethod(function, env, functionEnv, args.toList)
      execute(function.getStatement, functionEnv)

      val returnValue = try {
        functionEnv.lookup("$return")
      } catch {
        case e: Exception => NullValue("$return")
      }

      //      returnValue match {
      //        case s: SymbolicValue => recordDefinedSymbolicCall(functionCall, s)
      //        case _ => {}
      //      }

      env.weaveDelta(functionEnv.getDelta())
      returnValue
    } else {
      return SymbolValue(e.toString, OakHeap.getIndex, SymbolFlag.RECURSION)
    }

  }

  private def evaluateFunArrayExpr(arrayExpr: FunArrayExpr, env: Environment): OakValue = {
    val keys = arrayExpr._keys.toList
    val values = arrayExpr._values.toList

    assert(keys.size == values.size)
    val array = new ArrayValue()
    for (i <- 0 to keys.size - 1) {
      array.set(if (keys(i) != null) evaluate(keys(i), env) else IntValue(i), evaluate(values(i), env), env)
    }
    array
  }

  private def evaluateArrayGetExpr(arrayGet: ArrayGetExpr, env: Environment): OakValue = {

    val exx = arrayGet._expr
    if (exx.toString.equals("$_POST") || exx.toString.equals("$_GET") || exx.toString.equals("$_SESSION") || exx.toString.equals("$_SERVER")) {
      return SymbolValue(exx.toString, OakHeap.getIndex, SymbolFlag.BUILTIN_VALUE)
    }

    val expr = evaluate(exx, env)
    val index = evaluate(arrayGet._index, env)
    expr match {
      case null => null
      case av: ArrayValue => {
        val value = try {
          if (index.isInstanceOf[StringValue]) {
            index.asInstanceOf[StringValue].setLocation(null)
          }
          av.get(index, env)
        } catch {
          case t: ArrayIndexOutOfBoundsException => NullValue("Bazinga")
        }
        value
      }
      case v: OakValue => {
        NullValue("evaluateArrayGetExpr02 " + v.getClass)
      }
    }
  }

  private def evaluateBinaryAppendExpr(b: BinaryAppendExpr, env: Environment): OakValue = {
    /* Get all values that are being concatenated*/
    val expressions = ListBuffer[Expr]()
    var expr = b

    expressions.append(expr.getValue)
    do {
      expr = expr.getNext
      expressions.append(expr.getValue)
    } while (expr.getNext != null)

    val value = new OakValueSequence(expressions.toList.map { e => try { evaluate(e, env) } catch { case aioob: ArrayIndexOutOfBoundsException => SymbolValue(e.toString(), OakHeap.getIndex, SymbolFlag.EXPR_LEFT_UNEVALUATED) } })
    value
  }

  /*
  * $objExpr->methodName(args...)
  * 
  */
  private def evaluateObjectMethodExpr(e: ObjectMethodExpr, env: Environment): OakValue = {
    val methodName = e._methodName.toString()
    val args = e._args.toList
    evaluateMethodCall(e, methodName, args, env)
  }

  private def evaluateObjectNewExpr(b: ObjectNewExpr, env: Environment): OakValue = {
    val name = b._name
    val args = b._args

    val objectValue = try {

      val constructor = env.getClassDef(name).getConstructor(args.size) // match by number of args

      /** Create a new object Value */
      val obj = ObjectValue("Object Doe", env.getClassDef(name))
      val objEnv = Environment.createObjectEnvironment(env, obj)

      /* Execute constructor in the object environment and keep its variable $this:
     *  
     *  - assign all evaluated arg expressions to the constructor args
     *   */
      assert(constructor.args.size == args.size)
      (constructor.args zip args).foreach {
        case (name, expr) => objEnv.update("$" + name, evaluate(expr, env))
      }

      execute(constructor.statement, objEnv)

      objEnv.lookup("$this") match {
        case obj: ObjectValue => {
          env.weaveDelta(objEnv.getDelta())
          //thisArray.getKeys.foreach {
          //  key => println(key);obj.set(key.asInstanceOf[StringValue].value.toString, thisArray.get(key, env), env)
          //}
          obj
        }
        case n: NullValue => {
          SymbolValue(n.toString(), OakHeap.getIndex, SymbolFlag.AMBIGUOUS_VALUE)
        }
        case sym: SymbolicValue => {
          SymbolValue(sym.toString(), OakHeap.getIndex, SymbolFlag.AMBIGUOUS_VALUE)
        }
      }
    } catch {
      case nsee: NoSuchElementException => {
        try {
          env.getClassDef(name).getDefaultObject(env)
        } catch {
          case nsee2: NoSuchElementException => SymbolValue("", OakHeap.getIndex, SymbolFlag.AMBIGUOUS_VALUE)
        }
      }
    }
    objectValue
  }

  private def evaluateUnarySuppressErrorExpr(t: UnarySuppressErrorExpr, env: Environment): OakValue = {
    evaluate(t._expr, env)
  }

  private def evaluateThisFieldExpr(t: ThisFieldExpr, env: Environment): OakValue = {
    val fieldName = t._name.toString()
    val obj = env.lookup("$this")
    return obj match {
      case objectValue: ObjectValue => {
        val bener = objectValue.getFields()
        try {
          val ref = objectValue.getFields().getRef(StringValue(fieldName, "", 0))
          env.extract(ref)
        } catch {
          case _ => NullValue("evaluateThisFieldExpr") //objectValue.getClassDef().getFieldMap().get(fieldName).get
        }
      }
      case _ => SymbolValue(t.toString, OakHeap.getIndex(), SymbolFlag.AMBIGUOUS_VALUE)
    }
  }

  private def evaluateLiteralNullExpr(t: LiteralNullExpr, env: Environment): OakValue = {
    NullValue("LiteralNullExpr")
  }

  private def evaluateBinaryAssignExpr(e: BinaryAssignExpr, env: Environment): OakValue = {
    val name = e._var
    val expr = e._value

    return name match {

      /* name is a variable */
      case n: VarExpr => {

        if (expr.isInstanceOf[BinaryAssignExpr]) {
          var names = Set(name.toString)
          var assignExpr = expr
          while (assignExpr.isInstanceOf[BinaryAssignExpr]) {
            val o = assignExpr.asInstanceOf[BinaryAssignExpr]
            val namez = o._var.toString
            names += namez
            assignExpr = o._value
          }
          val value = evaluate(assignExpr, env)
          names.foreach { n => env.update(n, value) }
          return null
        }

        try {
          val value = evaluate(expr, env)
          val valueX = value match {
            case sv: StringValue => {
              sv
            }
            case _ => {
              value
            }
          }
          env.update(name.toString, valueX)
        } catch {
          case vnfe: VariableNotFoundException => {

          }
        }
        null
      }

      /**
       * Assignment to array field:
       *
       * $<arrayName>..[<Expr>]* = <Expr>
       */
      case a: ArrayGetExpr => {

        /* Gets the list of array expression indices, e.g.
         * get_array_indices("$array[0]['a'][$b]") will return List('0', 'a', '$c') 
         */
        val get_array_indices = (e: String) => e.split("\\[").tail.map(s => s.slice(0, s.size - 1).replaceAll("\"", "")).toList

        /* Gets the identifier of the array, e.g.
         * get_array_name("$array[0]['a'][$b]") will return "array"
         */
        val get_array_name = (e: String) => ("\\](\\s|\\d|[^(\\[|\\])])*\\[".r replaceAllIn (e.toString.reverse, "")).reverse

        val value = evaluate(expr, env)
        val array_name = get_array_name(a.toString)

        val array_indices = get_array_indices(a.toString).map {
          i =>
            {
              if (i.startsWith("$")) {
                env.lookup(i) match {
                  case sv: StringValue => {

                    /* Since this StringValue is used by the ArrayValue internally, 
                      * we omit the location information. */
                    sv.setLocation(null)
                    sv
                  }
                  case ov: OakValue => ov
                }
              } else {
                try {
                  IntValue(i.toLong)
                } catch {
                  case e: Exception => StringValue(i, "", 0)
                }
              }
            }
        }

        /**
         * Returns the reference to the next to last element, the array value to
         * store the <expr> in.
         * @param ref 			Reference to the variable $array
         * @param indices		List of indices
         *
         * @return Reference to the array value to manipulate
         */
        def recursive_array_lookup(ref: OakVariable, indices: List[OakValue]): OakVariable = {
          indices.size match {
            case 1 => ref
            case _ => env.extract(ref) match {
              case vref: OakVariable => {
                recursive_array_lookup(vref, indices)
              }
              case av: ArrayValue => {
                if (!av.hasKey(indices.head)) {
                  val temp_ref = OakVariable(array_name + OakHeap.getIndex(), "")
                  env.insert(temp_ref, new ArrayValue())
                  av.setRef(indices.head, temp_ref)
                }
                recursive_array_lookup(av.getRef(indices.head), indices.tail)
              }
              case n: OakValue => {
                null
              }
            }

          }
        }
        val array_reference_first = try {
          env.getRef(array_name, limitScope = false)
        } catch {
          case vnfe: VariableNotFoundException => {
            val newRef = OakVariable(array_name + OakHeap.getIndex(), "")
            env.insert(newRef, new ArrayValue())
            env.setRef(array_name, newRef)
            newRef
          }
        }

        /*
         * This is the reference pointing to the (prospective) array value to assign
         * the element to. If this value does not exist yet, i.e. the pointer refers to
         * an undefined value, declare a new ArrayValue and 
         */
        val array_reference_last = recursive_array_lookup(array_reference_first, array_indices)
        val av = try {
          env.extract(array_reference_last) //.set(array_indices.last, value, env)
        } catch {
          case vnfe: VariableNotFoundException => {
            val new_array_value = new ArrayValue()
            env.insert(array_reference_last, new_array_value)
            new_array_value
          }
        }

        av match {
          case av: ArrayValue => {
            av.set(array_indices.last, value, env)
          }
          case _ => {
            // FIXME What about Choices?
          }
        }

        null
      }

      /**
       * Assignment to object field (inside method declaration):
       *  $this -> <fieldName> = <Expr>
       */
      case dis: ThisFieldExpr => {
        val fieldName = dis._name

        /* $this points to an array, hence we perform an assignment to $this[name] */
        env.lookup("$this") match {
          case thisValue: ObjectValue => {
            val value = evaluate(expr, env)
            val valueX = value match {
              case sv: StringValue => sv
              case _ => value
            }
            thisValue.set(fieldName.toString(), valueX, env)
            env.update("$this", thisValue)
          }
          case n: NullValue => {}
          case s: SymbolicValue => {
            //logger.info("Object is symbolic")
          }
        }
        null
      }

      /**
       * Assignment to object field:
       * $<objName> -> <fieldName> = <Expr>
       */
      case of: ObjectFieldExpr => {
        val objName = of._objExpr.toString()
        try {
          val obj = env.lookup(objName).asInstanceOf[ObjectValue]
          val fieldName = of._name.toString()
          val ref = obj.getFieldRef(fieldName)
          val value = evaluate(expr, env)
          val valueX = value match {
            case sv: StringValue =>
              sv
            case _ => value
          }
          env.insert(ref, valueX)
        } catch {
          case vnfe: VariableNotFoundException => {}
          case cce: ClassCastException => {}
        }
        null
      }

      case at: ArrayTailExpr => {
        val name = at._expr.toString
        val array = try {
          env.lookup(name).asInstanceOf[ArrayValue]
        } catch {
          case t: Throwable => new ArrayValue()
        }

        // $name[] = <expr>
        expr match {
          case a: ArrayGetExpr => {
            val value = evaluate(expr, env)
            val index = evaluate(a._index, env)

            array.set(index, value, env)
            env.update(name, array)
          }
          case _ => {
            // $abc[] = xyyz;
            val av = new ArrayValue()
            av.set(IntValue(0), evaluate(expr, env), env)
            env.update(name, av)
            return null
          }
        }
        null
      }
      case tfve: ThisFieldVarExpr => {
        val fieldName = StringValue(tfve._nameExpr.toString, tfve._location.getFileName, tfve._location.getLineNumber)

        val thisArray = env.lookup("$this")
        thisArray match {
          case o: ObjectValue => {
            o.set(fieldName.toString(), evaluate(expr, env), env)
            env.update("$this", o)
          }
          case c: Choice => {
            val func = (o: ObjectValue) => {
              o.set(fieldName.toString(), evaluate(expr, env), env)
              env.update("$this", c)
            }
            c.applyToObjects(func)
          }
          case _ => {}

        }
        null
      }

      case e: ClassFieldExpr => {
        val className = e._className.toString()
        val fieldName = e._varName.toString()
        env.setStaticClassField(className, fieldName, evaluate(expr, env))
        null
      }
      case _ => throw new RuntimeException(name.getClass + " unexpected " + expr.toString())
    }
  }

  private def evaluateLiteralLongExpr(e: LiteralLongExpr, env: Environment): OakValue = {
    val value = e._value
    IntValue(value)
  }

  private def evaluateConditionalExpr(e: ConditionalExpr, env: Environment): OakValue = {

    val test = e._test
    val testV = evaluate(test, env)
    val trueExpr = e._trueExpr
    val falseExpr = e._falseExpr

    if (testV.isInstanceOf[SymbolicValue]) {
      Choice.optimized(Constraint(test.toString), evaluate(trueExpr, env), evaluate(falseExpr, env))
    } else {
      testV match {
        case b: BooleanValue => {
          if (b.value) {
            evaluate(trueExpr, env)
          } else {
            evaluate(falseExpr, env)
          }
        }
        case _ => Choice.optimized(Constraint(test.toString), evaluate(trueExpr, env), evaluate(falseExpr, env)) //throw new RuntimeException("Expression did not evaluate to BooleanExpression.")
      }
    }
  }

  private def evaluateUnaryMinusExpr(e: UnaryMinusExpr, env: Environment): OakValue = {
    val value = evaluate(e._expr, env)
    value match {
      case n: NumericValue => {
        IntValue(-1) * n
      }
      case _ => SymbolValue(e.toString, OakHeap.getIndex, SymbolFlag.AMBIGUOUS_VALUE)
    }
  }

  private def evaluateFunIssetExpr(e: FunIssetExpr, env: Environment): OakValue = {
    return try {
      env.lookup(e._expr.toString)
      BooleanValue(true)
    } catch {
      case e: VariableNotFoundException => BooleanValue(false)
    }
  }

  private def evaluateConstExpr(e: ConstExpr, env: Environment): OakValue = {
    env.getConstant(e._var)
  }

  private def evaluateFunEmptyExpr(e: FunEmptyExpr, env: Environment): OakValue = {
    val value = e._value
    val re = try {
      val empty = env.lookup(e.toString)
      if (empty == BooleanValue(false)) {
        BooleanValue(true)
      } else {
        BooleanValue(false)
      }
    } catch {
      case v: VariableNotFoundException => BooleanValue(true)
    }
    return re
  }

  private def evaluateToArrayExpr(e: ToArrayExpr, env: Environment): OakValue = {
    val expr = e._expr
    new ArrayValue()
  }

  private def evaluateClassMethodExpr(cme: ClassMethodExpr, env: Environment): OakValue = {
    try {
      val classDef = env.getClassDef(cme._className)

      if (cme._className equals cme._methodName.toString()) {
        val constructor = classDef.getConstructor(cme._args.size)
        execute(constructor.statement, env)
        null
      } else {
        val method = classDef.getMethods(cme._methodName.toString)
        val args = cme._args

        val classMethodCall = Call(cme._className + "::" + cme._methodName.toString(), (Paths.get(cme._location.getFileName), cme._location.getLineNumber), args.toList.map(e => evaluate(e, env)))
        //
        val cmEnv = Environment.createFunctionEnvironment(env, classMethodCall)
        prepareFunctionOrMethod(method, env, cmEnv, args.toList)

        if (!(env.getCalls().map(c => c.name) contains classMethodCall.name)) {
          execute(method.statement, cmEnv)
          val returnValue = try {
            env.lookup("$return")
          } catch {
            case e: VariableNotFoundException => NullValue("$return")
          }

          //          returnValue match {
          //            case s: SymbolicValue => recordDefinedSymbolicCall(classMethodCall, s)
          //            case _ => {}
          //          }

          returnValue
        } else {
          SymbolValue(cme.toString, OakHeap.getIndex, SymbolFlag.RECURSION)
        }
      }
    } catch {
      case nsee: NoSuchElementException => SymbolValue(cme.toString(), OakHeap.getIndex(), SymbolFlag.AMBIGUOUS_VALUE)
    }
  }

  private def evaluateThisMethodExpr(e: ThisMethodExpr, env: Environment): OakValue = {
    val methodName = e.asInstanceOf[ObjectMethodExpr]._methodName.toString()
    val args = e.asInstanceOf[ObjectMethodExpr]._args.toList
    evaluateMethodCall(e, methodName, args, env)
  }

  def evaluateMethodCall(e: Expr, methodName: String, args: List[Expr], env: Environment): OakValue = {

    /**
     * Recursively applies method to a variational object.
     * @param value Choice of objects or ObjectValue
     * @param methodName Name of the objects method
     * @param args List of argument expressions
     * @param Environment to start with
     *
     * @return Choice of return values
     */
    def applyMethod(value: OakValue, methodName: String, args: List[Expr], env: Environment): OakValue = {

      if (methodName equals "apply_filters") throw new RuntimeException

      value match {
        case objectValue: ObjectValue => {
          val methodCall = Call(objectValue.objectClass.name + "." + methodName, (Paths.get(e._location.getFileName), e._location.getLineNumber), args.toList.map(e => evaluate(e, env)))
          if (!(env.getCalls().map { c => c.name } contains methodCall.name)) {
            val methodEnv = Environment.createMethodEnvironment(env, objectValue, methodCall)
            prepareFunctionOrMethod(objectValue.getClassDef().getMethods(methodName), env, methodEnv, args)
            execute(objectValue.getClassDef().getMethods(methodName).statement, methodEnv)
            env.weaveDelta(methodEnv.getDelta())
            val ret = try {
              methodEnv.lookup("$return")
            } catch {
              case _: Throwable => NullValue("$return")
            }

            //            ret match {
            //              case s: SymbolicValue => recordDefinedSymbolicCall(methodCall, s)
            //              case _ => {}
            //            }

            return ret
          } else {
            NullValue("applyMethod01")
          }
        }

        case choice: Choice => {
          val branches = Environment.fork(env, List(choice.p))
          val r1 = applyMethod(choice.v1, methodName, args, branches.head)
          val r2 = applyMethod(choice.v2, methodName, args, branches(1))
          env.weaveDelta(BranchEnv.join(List(branches(0), branches(1)), List(choice.p)))
          Choice.optimized(choice.p, r1, r2)
          //                    NullValue("")
        }
        case _ => {
          NullValue("applyMethod02") //SymbolValue(e.toString(), OakHeap.getIndex(), SymbolFlag.FUNCTION_CALL)
        }
      }
    }
    val obj = e match {
      case dis: ThisMethodExpr => {
        env.lookup("$this") match {
          case o: ObjectValue => o
          case v: OakValue => v
        }
      }
      case obj: ObjectMethodExpr => {
        val r = evaluate(obj._objExpr, env)
        r match {
          case o: ObjectValue => o
          case v: OakValue => v
          case null => null
        }
      }
    }
    applyMethod(obj, methodName, args, env)

  }
  private def evaluateToLongExpr(e: ToLongExpr, env: Environment): OakValue = {
    val expr = e._expr
    SymbolValue(expr.toString, OakHeap.getIndex, SymbolFlag.TYPE_CONVERSION)
  }

  private def evaluateObjectFieldExpr(e: ObjectFieldExpr, env: Environment): OakValue = {
    // $this->
    val obj = evaluate(e._objExpr, env)

    val value = if (obj.isInstanceOf[ObjectValue]) {
      try {
        obj.asInstanceOf[ObjectValue].get(e._name.toString(), env)
      } catch {
        case aioob: ArrayIndexOutOfBoundsException => NullValue("evaluateObjectField")
      }
    } else {
      SymbolValue(e.toString, OakHeap.getIndex, SymbolFlag.AMBIGUOUS_VALUE)
    }
    value
  }

  private def evaluateThisFieldVarExpr(e: ThisFieldVarExpr, env: Environment): OakValue = {
    val thisV = env.lookup("$this").asInstanceOf[ArrayValue]
    val t = thisV.get(StringValue(e._nameExpr.toString, e._location.getFileName, e._location.getLineNumber), env)
    t
  }

  private def evaluateBinaryInstanceOfExpr(e: BinaryInstanceOfExpr, env: Environment): OakValue = {
    SymbolValue(e.toString, OakHeap.getIndex, SymbolFlag.TYPE_CONVERSION)
  }

  private def evaluateToStringExpr(e: ToStringExpr, env: Environment): OakValue = {
    SymbolValue(e._expr.toString, OakHeap.getIndex, SymbolFlag.TYPE_CONVERSION)
  }

  private def evaluateThisExpr(e: ThisExpr, env: Environment): OakValue = {
    env.lookup("$this") match {
      case obj: ObjectValue => {
        obj
      }
      case sym: SymbolicValue => {
        sym
      }
    }
  }

  private def evaluateToBooleanExpr(e: ToBooleanExpr, env: Environment): OakValue = {
    SymbolValue(e._expr.toString, OakHeap.getIndex, SymbolFlag.TYPE_CONVERSION)
  }

  private def evaluateToObjectExpr(e: ToObjectExpr, env: Environment): OakValue = {
    SymbolValue(e._expr.toString, OakHeap.getIndex, SymbolFlag.TYPE_CONVERSION)
  }

  private def evaluateClassFieldExpr(e: ClassFieldExpr, env: Environment): OakValue = {
    val className = e._className
    val varname = e._varName
    env.getStaticClassField(className, varname.toString)
  }

  private def evaluateClassConstExpr(e: ClassConstExpr, env: Environment): OakValue = {
    val className = e._className
    val name = e._name
    try {
      val constants = env.getClassDef(className).getConstants()
      if (constants.keySet contains name) {
        return constants.get(name)
      } else {
        StringValue("", "", 0)
      }
    } catch {
      case nsee: NoSuchElementException => SymbolValue(e.toString(), OakHeap.getIndex(), SymbolFlag.AMBIGUOUS_VALUE)
    }
  }

  private def evaluateUnaryPreIncrementExpr(e: UnaryPreIncrementExpr, env: Environment): OakValue = {
    e._expr match {
      case ag: ArrayGetExpr => {
        val array = evaluate(ag, env).asInstanceOf[ArrayValue]
        val ref = array.getRef(evaluate(ag._index, env))
        env.insert(ref, SymbolValue(e.toString(), OakHeap.getIndex(), SymbolFlag.AMBIGUOUS_VALUE))
      }
      case _ => {
        env.update(e._expr.toString(), SymbolValue(e.toString(), OakHeap.getIndex(), SymbolFlag.AMBIGUOUS_VALUE))
      }
    }
    null
  }

  // TODO icmplement ObjectNewVarExpr
  private def evaluateObjectNewVarExpr(e: ObjectNewVarExpr, env: Environment): OakValue = {
    SymbolValue(e.toString, OakHeap.getIndex, SymbolFlag.EXPR_UNIMPLEMENTED)
  }

  private def evaluateFunCloneExpr(e: FunCloneExpr, env: Environment): OakValue = {
    evaluate(e._expr, env) match {
      case obj: ObjectValue => {
        obj.cloneObjectValue()
      }
      case _ => SymbolValue(e.toString, OakHeap.getIndex, SymbolFlag.EXPR_UNEVALUATED)
    }
  }

  // TODO implement
  private def evaluateCallVarExpr(e: CallVarExpr, env: Environment): OakValue = {
    SymbolValue(e.toString, OakHeap.getIndex, SymbolFlag.FUNCTION_CALL)
  }

  private def evaluateObjectFieldVarExpr(e: ObjectFieldVarExpr, env: Environment): OakValue = {
    evaluate(e._objExpr, env) match {
      case obj: ObjectValue => {
        obj.getFieldRef(e._nameExpr.toString())
      }
      case _ => {
        SymbolValue(e.toString, OakHeap.getIndex, SymbolFlag.EXPR_UNEVALUATED)
      }
    }
  }
  private def evaluateUnaryRefExpr(e: UnaryRefExpr, env: Environment): OakValue = {
    env.getRef(e._expr.toString)
  }

  private def evaluateFunDieExpr(e: FunDieExpr, env: Environment): OakValue = {
    val value = evaluate(e._value, env)
    env.addOutput(DNode.createDNode(value, e._location))
    null
  }

  private def evaluateFunIncludeExpr(e: FunIncludeExpr, env: Environment): OakValue = {
    include(e._expr, env)
    null
  }

  def include(expr: Expr, env: Environment) {
    val oldURL = this.path
    if (!(evaluate(expr, env).isInstanceOf[SymbolicValue])) {

      val includePaths = getInlcudePath(expr, env)
      includePaths.foreach {
        includePath =>
          {
            var includePaf = includePath
            if (!includePaf.toString.startsWith("/")) {
              includePaf = Paths.get(this.rootPath + "/" + includePaf.toString())
            }
            if (includePaf.toFile.exists()) {
              val program = this.engine.loadFromFile(includePaf)
              this.includes = this.includes.push(includePaf)
              this.path = includePath

              logger.info(".." + oldURL.toString().takeRight(30) + " includes .." + includePaf.toString.takeRight(30))
              execute(program, env)

              this.includes = this.includes.pop
              this.path = oldURL
            } else {
              logger.error(includePaf + " is not a file!")
            }
          }
      }
    }
  }

  private def evaluateFunIncludeOnceExpr(e: FunIncludeOnceExpr, env: Environment): OakValue = {
    include(e._expr, env)
    null
  }

  def evaluateToDoubleExpr(e: ToDoubleExpr, env: Environment): OakValue = {
    SymbolValue(e._expr.toString, OakHeap.getIndex, SymbolFlag.TYPE_CONVERSION)
  }

  def evaluateUnaryBitNotExpr(e: UnaryBitNotExpr, env: Environment): OakValue = {
    SymbolValue(e._expr.toString, OakHeap.getIndex, SymbolFlag.TYPE_CONVERSION)
  }

  // __FILE__
  def evaluateConstFileExpr(e: ConstFileExpr, env: Environment): OakValue = {
    StringValue(this.path.toString, "", 0)
  }

  def evaluate(e: Expr, env: Environment): OakValue = {

    e match {
      case e: LiteralExpr => {
        evaluateLiteralExpr(e, env)
      }
      case e: LiteralUnicodeExpr => {
        evaluateLiteralUnicodeExpr(e, env)
      }
      case e: VarExpr => {
        evaluateVarExpr(e, env)
      }
      case e: UnaryNotExpr => {
        evaluateUnaryNotExpr(e, env)
      }
      case e: UnaryMinusExpr => {
        evaluateUnaryMinusExpr(e, env)
      }
      case ae: AbstractBinaryExpr => {
        evaluateAbstractBinaryExpr(ae, env)
      }
      case e: CallExpr => {
        evaluateCallExpr(e, env)
      }
      case arrayExpr: FunArrayExpr => {
        evaluateFunArrayExpr(arrayExpr, env)
      }
      case arrayGet: ArrayGetExpr => {
        evaluateArrayGetExpr(arrayGet, env)
      }
      case b: BinaryAppendExpr => {
        evaluateBinaryAppendExpr(b, env)
      }
      case o: ObjectNewExpr => {
        evaluateObjectNewExpr(o, env)
      }
      case e: ThisMethodExpr => {
        evaluateThisMethodExpr(e, env)
      }
      case o: ObjectMethodExpr => {
        evaluateObjectMethodExpr(o, env)
      }
      case t: ThisFieldExpr => {
        evaluateThisFieldExpr(t, env)
      }
      case n: LiteralNullExpr => {
        evaluateLiteralNullExpr(n, env)
      }
      case a: BinaryAssignExpr => {
        evaluateBinaryAssignExpr(a, env)
      }
      case u: UnarySuppressErrorExpr => {
        evaluateUnarySuppressErrorExpr(u, env)
      }
      case e: LiteralLongExpr => {
        evaluateLiteralLongExpr(e, env)
      }
      case ce: ConditionalExpr => {
        evaluateConditionalExpr(ce, env)
      }
      case e: FunIssetExpr => {
        evaluateFunIssetExpr(e, env)
      }
      case e: ConstExpr => {
        evaluateConstExpr(e, env)
      }
      case e: FunEmptyExpr => {
        evaluateFunEmptyExpr(e, env)
      }
      case e: ToArrayExpr => {
        evaluateToArrayExpr(e, env)
      }
      case e: ClassMethodExpr => {
        evaluateClassMethodExpr(e, env)
      }
      case e: ToLongExpr => {
        evaluateToLongExpr(e, env)
      }
      case e: ToLongExpr => {
        evaluateToLongExpr(e, env)
      }
      case e: ToLongExpr => {
        evaluateToLongExpr(e, env)
      }
      case e: ToStringExpr => {
        evaluateToStringExpr(e, env)
      }
      case e: ThisExpr => {
        evaluateThisExpr(e, env)
      }
      case e: ToBooleanExpr => {
        evaluateToBooleanExpr(e, env)
      }
      case e: BinaryInstanceOfExpr => {
        evaluateBinaryInstanceOfExpr(e, env)
      }
      case e: ObjectFieldExpr => {
        evaluateObjectFieldExpr(e, env)
      }
      case e: ToObjectExpr => {
        evaluateToObjectExpr(e, env)
      }
      case e: ClassFieldExpr => {
        evaluateClassFieldExpr(e, env)
      }
      case e: ClassConstExpr => {
        evaluateClassConstExpr(e, env)
      }
      case e: ObjectNewVarExpr => {
        evaluateObjectNewVarExpr(e, env)
      }
      case e: UnaryRefExpr => {
        evaluateUnaryRefExpr(e, env)
      }
      case e: FunCloneExpr => {
        evaluateFunCloneExpr(e, env)
      }
      case e: CallVarExpr => {
        evaluateCallVarExpr(e, env)
      }
      case e: ObjectFieldVarExpr => {
        evaluateObjectFieldVarExpr(e, env)
      }
      case e: UnaryPreIncrementExpr => {
        evaluateUnaryPreIncrementExpr(e, env)
      }
      case e: FunDieExpr => {
        evaluateFunDieExpr(e, env)
      }
      case e: BinaryOrExpr => {
        evaluateAbstractBinaryExpr(e, env)
      }
      case e: FunIncludeExpr => {
        evaluateFunIncludeExpr(e, env)
      }
      case e: FunIncludeOnceExpr => {
        evaluateFunIncludeOnceExpr(e, env)
      }
      case e: ToDoubleExpr => {
        evaluateToDoubleExpr(e, env)
      }
      case e: UnaryBitNotExpr => {
        evaluateUnaryBitNotExpr(e, env)
      }
      case e: ConstFileExpr => {
        evaluateConstFileExpr(e, env)
      }
      case null => null
      case _ => throw new RuntimeException(e.getClass + " " + e + " not implemented.") //return SymbolValue(e.toString(), 0, SymbolFlag.EXPR_UNIMPLEMENTED)
    }
  }

  /**
   * Gets the include path according to the passed Expr
   * @param expr IncludeExpression
   * @param env current Environment
   *
   * @return java.nio.Path includePath
   */
  def getInlcudePath(expr: Expr, env: Environment): List[Path] = {

    var paf = evaluate(expr, env) //

    val z = paf
    if (!(new File(paf.toString())).exists()) {
      val pathsRaw = paf match {
        case s: OakValueSequence => {
          // Flatten all choices
          val sequenceFlattened = s.getSequence.map {
            v =>
              v match {
                case c: Choice => new OakValueSequence(c.getElements.toList)
                case v: OakValue => v
              }
          }

          val tree = Tree.construct(sequenceFlattened)
          val paths = new ListBuffer[OakValueSequence]()
          tree.plainTraverse(paths)
          paths.toList
        }
        case v: OakValue => List(v)
      }

      val paths = pathsRaw.map {
        paf =>
          {
            var path = paf.toString.replace("\"", "")
            Paths.get(path)
          }
      }
      paths // TODO remove duplicates
    } else {
      List(Paths.get(paf.toString))
    }

  }

  private def defineFunctions(program: QuercusProgram, env: Environment) {
    /** Write all function definitions to the heap */
    val funcIterator = program.getFunctionList.iterator
    while (funcIterator.hasNext) {
      val func = env.defineFunction(funcIterator.next())
      env.defineFunction(func)
    }
  }

  /**
   * Assigns passed arguments to the corresponding function context (environment).
   *
   * @param function FunctionDef instance representing the function or method to execute
   * @param env Environment The current environment of the outer program (caller's) context
   * @param functionEnv Environment The function/method (callee's) context to prepare
   * @param args List of Exprs that are passed for the function/method call
   */
  def prepareFunctionOrMethod(function: FunctionDef, env: Environment, functionEnv: Environment, args: List[Any]) {
    (function.getArgs.slice(0, args.length) zip args).foreach {
      t =>
        {
          if (t._1.startsWith("&")) {
            try {
              val functionRef = env.getRef(t._2.toString)
              functionEnv.setRef("$" + t._1.replace("&", ""), functionRef)
              t._2 match {
                case e: Expr => functionEnv.insert(functionRef, evaluate(e, env))
                case v: OakValue => functionEnv.insert(functionRef, v)
              }

            } catch {
              case nsee: VariableNotFoundException => {
                val functionRef = OakVariable(t._1.toString, t._1.toString + OakHeap.getIndex())
                functionEnv.setRef("$" + t._1.replace("&", ""), functionRef)
                functionEnv.insert(functionRef, NullValue("prepareFunctionOrMethod01"))
              }
            }

          } else {
            val functionVal = t._2 match {
              case e: Expr => {
                val ev = evaluate(e, env)
                ev match {
                  case av: ArrayValue => {
                    val deepCopy = new ArrayValue()
                    av.array.foreach { case (k, v) => deepCopy.set(k, env.extract(v), functionEnv) }
                    deepCopy
                  }
                  case null => null
                  case value: OakValue => value
                }
              }
              case v: OakValue => v

            }
            functionEnv.update("$" + t._1.replace("&", ""), functionVal)
          }
        }
    }
    if (function.getArgs.length > args.length) {
      function.getArgs.slice(args.length, function.getArgs.length).foreach {
        a =>
          {
            val defaultValue = try {
              evaluate(function.defaults.get(a).get, env)
            } catch {
              case nsee: NoSuchElementException => NullValue("prepareFunctionOrMethod02")
            }
            functionEnv.update("$" + a.replace("&", ""), defaultValue)
          }
      }
    }
  }

}

object OakInterpreter {

  var symbolSet = Set[SymbolValue]()

}

object SymbolFlag extends Enumeration {
  val FUNCTION_CALL, EXPR_LEFT_UNEVALUATED, EXPR_UNEVALUATED, AMBIGUOUS_VALUE, EXPR_UNIMPLEMENTED, BUILTIN_VALUE, TYPE_CONVERSION, DUMMY, RECURSION = Value
}
