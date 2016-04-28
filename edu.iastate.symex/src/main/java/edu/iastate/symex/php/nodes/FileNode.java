package edu.iastate.symex.php.nodes;

import java.io.File;
import org.eclipse.php.internal.core.PHPVersion;
import org.eclipse.php.internal.core.ast.nodes.ASTParser;
import org.eclipse.php.internal.core.ast.nodes.Program;

import edu.iastate.symex.util.logging.MyLevel;
import edu.iastate.symex.util.logging.MyLogger;
import edu.iastate.symex.core.Env;
import edu.iastate.symex.datamodel.nodes.DataNode;
import edu.iastate.symex.datamodel.nodes.SpecialNode.ControlNode;
import edu.iastate.symex.datamodel.nodes.SpecialNode.UnsetNode;
import edu.iastate.symex.util.ASTHelper;
import edu.iastate.symex.util.FileIO;

/**
 * 
 * @author HUNG
 *
 */
public class FileNode {

	private final File file;
	private ProgramNode programNode = null;	// The AST node representing a PHP program
		
	/**
	 * Constructor: Creates a FileNode representing a PHP source file.
	 * @param file	The PHP file to be parsed
	 * @see {@link edu.iastate.symex.php.nodes.IncludeNode#execute(Env)}
	 */
	public FileNode(File file) {
		this.file=file;
		
		/*
		 * Prepare to parse the source file
		 */
		ASTParser parser = ASTParser.newParser(PHPVersion.PHP5, true);
		String source = FileIO.readStringFromFile(file);
		
		/*
		 * Parse the source file
		 */
		Program program = null;
		try {
			parser.setSource(source.toCharArray());

			//NOTE: Pass IProgressMonitor
			program = parser.createAST(); 
		} catch (Exception e) {
			MyLogger.log(MyLevel.JAVA_EXCEPTION, "In FileNode.java: Error parsing " + file + " (" + e.getMessage() + ")");
		}
		
		/*
		 * Create the ProgramNode
		 */
		if (program != null) {
			ASTHelper.inst.setPhpFileForPhpProgram(program, file, source);
			this.programNode = new ProgramNode(program);
		}
	}
	
	/**
	 * Executes a PHP file. See edu.iastate.symex.php.nodes.ExpressionNode.execute(Env)
	 * @param env
	 */
	public DataNode execute(Env env) {
		env.putFile(file, this);
		
		if (programNode == null)
			return UnsetNode.UNSET;
		
		Object backupOutput = env.backupOutputAtReturns();
		Object backupReturn = env.backupValueAtReturns();
		
		env.clearOutputAtReturns();
		env.clearValueAtReturns();
		
		env.pushFileToStack(file);
		DataNode control = programNode.execute(env);
		env.popFileFromStack();
		
		env.mergeCurrentOutputWithOutputAtReturns();
		DataNode retValue = env.getReturnValue();
		
		env.restoreOutputAtReturns(backupOutput);
		env.restoreValueAtReturns(backupReturn);
		
		if (control == ControlNode.EXIT) // EXIT
			return ControlNode.EXIT;
		else if (control instanceof ControlNode) // OK, RETURN, BREAK, CONTINUE
			return retValue;
		else {
			// TODO Handle multiple returned CONTROL values here
			return retValue;
		}
	}

}
