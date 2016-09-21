# Oak 
*Oak* is an extensible *symbolic interpreter* for the [PHP](http://php.net/) programming language written mostly in Scala. Most execution features are similar to symbolic execution, although, if possible, certain features may be executed concretely. This tool is inspired by the symbolic execution engine by [Varis/Symex](https://github.com/git1997/VarAnalysis) and additionally provides support for basic Object-Oriented Programming and a framework for standard library function plugins.

## Documentation
For documentation and further reading, please refer to the [wiki](https://github.com/smba/oak/wiki) of this repository.

## Requirements
* *JDK 8* and *Scala 2.11.8+*
* *sbt 0.11+*: [sbt](http://www.scala-sbt.org/index.html) is used for building the interpreter and resolving dependencies.

## Build [![Build Status](https://travis-ci.org/smba/oak.svg?branch=master)](https://travis-ci.org/smba/oak)
To build this project, clone this repository using
```git clone https://github.com/smba/oak.git```
and build the project using `sbt compile`.

### IDE Integration
To edit the interpreter [IntelliJ IDEA](https://www.jetbrains.com/idea/) or the [Scala IDE for Eclipse](http://scala-ide.org/) are recommended. You can import sbt projects directly into IntelliJ; for eclipse we use the plugin [sbteclipse](https://github.com/typesafehub/sbteclipse) to build eclipse projects. For Eclipse simply use

```sbt eclipse```
to build eclipse projects.

### Configuration (Eclipse only)
Additionally, if you might want to configure different variants of the interpreter, use the [Antenna](http://antenna.sourceforge.net/wtkpreprocess.php#eclipse_plugin) preprocessor. The eclipse plugin [FeatureIDE](http://wwwiti.cs.uni-magdeburg.de/iti_db/research/featureide/) might become handy when modeling additional features.

More information about configuration and features used can be found in the [wiki](https://github.com/smba/oak/wiki).

## Getting started
We provide two different interpretation modes. A good start may be to fiddle with `edu.cmu.cs.oak.analysis.RunOakForFile.scala`. Specify the input file and (if desired) an ouput path for the symolic output.

To analyse PHP systems from multiple/all entry points, use `edu.cmu.cs.oak.analysis.Coverage.scala`.

## Project structure
* `oak`: Interpreter infrastructure including tests
* `quercus`: [Quercus](http://quercus.caucho.com/) is a pure-Java implementation of the PHP language, we basically reuse the parser and AST fragments
* `kernel` and `hessian` are dependencies for quercus

## Contact 
Feel free to fork this project or contact the author with any question at ![mail](http://chxo.com/labelgen/labelgen.php?textval=s.muehlbauer%40tu-bs.de&font=COUR.TTF&size=12&bgcolor=%23f5f5f5&textcolor=%23000000&submit=create+image)
