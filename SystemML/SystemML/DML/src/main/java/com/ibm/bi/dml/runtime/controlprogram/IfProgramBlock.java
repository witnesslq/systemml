/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2014
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.runtime.controlprogram;

import java.util.ArrayList;

import com.ibm.bi.dml.hops.Hop;
import com.ibm.bi.dml.parser.IfStatementBlock;
import com.ibm.bi.dml.parser.Expression.ValueType;
import com.ibm.bi.dml.runtime.DMLRuntimeException;
import com.ibm.bi.dml.runtime.DMLUnsupportedOperationException;
import com.ibm.bi.dml.runtime.instructions.Instruction;
import com.ibm.bi.dml.runtime.instructions.CPInstructions.BooleanObject;
import com.ibm.bi.dml.runtime.instructions.CPInstructions.CPInstruction;
import com.ibm.bi.dml.runtime.instructions.CPInstructions.ComputationCPInstruction;
import com.ibm.bi.dml.runtime.instructions.CPInstructions.Data;
import com.ibm.bi.dml.runtime.instructions.CPInstructions.ScalarObject;
import com.ibm.bi.dml.runtime.instructions.CPInstructions.StringObject;
import com.ibm.bi.dml.runtime.instructions.CPInstructions.CPInstruction.CPINSTRUCTION_TYPE;
import com.ibm.bi.dml.runtime.instructions.Instruction.INSTRUCTION_TYPE;
import com.ibm.bi.dml.runtime.instructions.SQLInstructions.SQLScalarAssignInstruction;


public class IfProgramBlock extends ProgramBlock 
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2014\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	
	private ArrayList<Instruction> _predicate;
	private String _predicateResultVar;
	private ArrayList <Instruction> _exitInstructions ;
	private ArrayList<ProgramBlock> _childBlocksIfBody;
	private ArrayList<ProgramBlock> _childBlocksElseBody;
	
	public IfProgramBlock(Program prog, ArrayList<Instruction> predicate) throws DMLRuntimeException{
		super(prog);
		
		_childBlocksIfBody = new ArrayList<ProgramBlock>();
		_childBlocksElseBody = new ArrayList<ProgramBlock>();
		
		_predicate = predicate;
		_predicateResultVar = findPredicateResultVar ();
		_exitInstructions = new ArrayList<Instruction>();
	}
	
	public ArrayList<ProgramBlock> getChildBlocksIfBody()
		{ return _childBlocksIfBody; }

	public void setChildBlocksIfBody(ArrayList<ProgramBlock> blocks)
		{ _childBlocksIfBody = blocks; }
	
	public void addProgramBlockIfBody(ProgramBlock pb)
		{ _childBlocksIfBody.add(pb); }	
	
	public ArrayList<ProgramBlock> getChildBlocksElseBody()
		{ return _childBlocksElseBody; }

	public void setChildBlocksElseBody(ArrayList<ProgramBlock> blocks)
		{ _childBlocksElseBody = blocks; }
	
	public void addProgramBlockElseBody(ProgramBlock pb)
		{ _childBlocksElseBody.add(pb); }
	
	public void printMe() {
		
		LOG.debug("***** if current block predicate inst: *****");
		for (Instruction cp : _predicate){
			cp.printMe();
		}
		
		LOG.debug("***** children block inst --- if body : *****");
		for (ProgramBlock pb : this._childBlocksIfBody){
			pb.printMe();
		}
	
		LOG.debug("***** children block inst --- else body : *****");
		for (ProgramBlock pb: this._childBlocksElseBody){
			pb.printMe();
		}
		
		LOG.debug("***** current block inst exit: *****");
		for (Instruction i : this._exitInstructions) {
			i.printMe();
		}	
	}


	public void setExitInstructions2(ArrayList<Instruction> exitInstructions){
		_exitInstructions = exitInstructions;
	}

	public void setExitInstructions1(ArrayList<Instruction> predicate){
		_predicate = predicate;
	}
	
	public void addExitInstruction(Instruction inst){
		_exitInstructions.add(inst);
	}
	
	public ArrayList<Instruction> getPredicate(){
		return _predicate;
	}

	public void setPredicate(ArrayList<Instruction> predicate) {
		_predicate = predicate;
		_predicateResultVar = findPredicateResultVar ();
	}
	
	public String getPredicateResultVar(){
		return _predicateResultVar;
	}
	
	public void setPredicateResultVar(String resultVar) {
		_predicateResultVar = resultVar;
	}
	
	public ArrayList<Instruction> getExitInstructions(){
		return _exitInstructions;
	}
	
	@Override
	public void execute(ExecutionContext ec) 
		throws DMLRuntimeException, DMLUnsupportedOperationException
	{	
		BooleanObject predResult = executePredicate(ec); 
	
		//execute if statement
		if(predResult.getBooleanValue())
		{	
			try 
			{	
				for( ProgramBlock pb : _childBlocksIfBody )
					pb.execute(ec);
			}
			catch(Exception e)
			{
				throw new DMLRuntimeException(this.printBlockErrorLocation() + "Error evaluating if statement body ", e);
			}
		}
		else
		{
			try 
			{	
				for( ProgramBlock pb : _childBlocksElseBody )
					pb.execute(ec);
			}
			catch(Exception e)
			{
				throw new DMLRuntimeException(this.printBlockErrorLocation() + "Error evaluating else statement body ", e);
			}	
		}
		
		//execute exit instructions
		try { 
			executeInstructions(_exitInstructions, ec);
		}
		catch (Exception e){
			
			throw new DMLRuntimeException(this.printBlockErrorLocation() + "Error evaluating if exit instructions ", e);
		}
	}
	
	/**
	 * 
	 * @param ec
	 * @return
	 * @throws DMLRuntimeException
	 * @throws DMLUnsupportedOperationException
	 */
	private BooleanObject executePredicate(ExecutionContext ec) 
		throws DMLRuntimeException, DMLUnsupportedOperationException 
	{
		BooleanObject result = null;
		try
		{
			if( _predicate!=null && _predicate.size()>0 )
			{
				if( _sb != null )
				{
					IfStatementBlock isb = (IfStatementBlock)_sb;
					Hop predicateOp = isb.getPredicateHops();
					boolean recompile = isb.requiresPredicateRecompilation();
					result = (BooleanObject) executePredicate(_predicate, predicateOp, recompile, ValueType.BOOLEAN, ec);
				}
				else
					result = (BooleanObject) executePredicate(_predicate, null, false, ValueType.BOOLEAN, ec);
			}
			else 
			{
				//get result var
				ScalarObject scalarResult = null;
				Data resultData = ec.getVariable(_predicateResultVar);
				if ( resultData == null ) {
					//note: resultvar is a literal (can it be of any value type other than String, hence no literal/varname conflict) 
					scalarResult = ec.getScalarInput(_predicateResultVar, ValueType.BOOLEAN, true);
				}
				else {
					scalarResult = ec.getScalarInput(_predicateResultVar, ValueType.BOOLEAN, false);
				}
				
				//check for invalid type String 
				if (scalarResult instanceof StringObject)
					throw new DMLRuntimeException(this.printBlockErrorLocation() + "\nIf predicate variable "+ _predicateResultVar + " evaluated to string " + scalarResult + " which is not allowed for predicates in DML");
				
				//process result
				if( scalarResult instanceof BooleanObject )
					result = (BooleanObject)scalarResult;
				else
					result = new BooleanObject( scalarResult.getBooleanValue() ); //auto casting
			}
		}
		catch(Exception ex)
		{
			throw new DMLRuntimeException(this.printBlockErrorLocation() + "Failed to evaluate the IF predicate.", ex);
		}
		
		if ( result == null )
			throw new DMLRuntimeException(this.printBlockErrorLocation() + "Failed to evaluate the IF predicate.");
		
		return result;
	}
	
	private String findPredicateResultVar ( ) {
		String result = null;
		for ( Instruction si : _predicate ) {
			if ( si.getType() == INSTRUCTION_TYPE.CONTROL_PROGRAM && ((CPInstruction)si).getCPInstructionType() != CPINSTRUCTION_TYPE.Variable ) {
				result = ((ComputationCPInstruction) si).getOutputVariableName();  
			}
			else if(si instanceof SQLScalarAssignInstruction)
				result = ((SQLScalarAssignInstruction)si).getVariableName();
		}
		return result;
	}
	
	public String printBlockErrorLocation(){
		return "ERROR: Runtime error in if program block generated from if statement block between lines " + _beginLine + " and " + _endLine + " -- ";
	}
	
}