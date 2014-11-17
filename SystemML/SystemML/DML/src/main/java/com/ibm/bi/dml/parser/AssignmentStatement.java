/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2014
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.parser;

import java.util.ArrayList;

import com.ibm.bi.dml.api.DMLScript;
import com.ibm.bi.dml.debugger.DMLBreakpointManager;
import com.ibm.bi.dml.hops.OptimizerUtils;


public class AssignmentStatement extends Statement
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2014\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
		
	private ArrayList<DataIdentifier> _targetList;
	private Expression _source;
	 
	// rewrites statement to support function inlining (creates deep copy)
	public Statement rewriteStatement(String prefix) throws LanguageException{
				
		// rewrite target (deep copy)
		DataIdentifier newTarget = (DataIdentifier)_targetList.get(0).rewriteExpression(prefix);
		
		// rewrite source (deep copy)
		Expression newSource = _source.rewriteExpression(prefix);
		
		// create rewritten assignment statement (deep copy)
		AssignmentStatement retVal = new AssignmentStatement(newTarget, newSource,this.getBeginLine(), 
											this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
		
		return retVal;
	}
	
	
	public AssignmentStatement(DataIdentifier t, Expression s) {

		_targetList = new ArrayList<DataIdentifier>();
		_targetList.add(t);
		_source = s;
	}
	
	
	public AssignmentStatement(DataIdentifier t, Expression s, int beginLine, int beginCol, int endLine, int endCol) throws LanguageException{
		
		_targetList = new ArrayList<DataIdentifier>();
		_targetList.add(t);
		_source = s;
	
		setBeginLine(beginLine);
		setBeginColumn(beginCol);
		setEndLine(endLine);
		setEndColumn(endCol);
		
	}
	
	public DataIdentifier getTarget(){
		return _targetList.get(0);
	}
	
	public ArrayList<DataIdentifier> getTargetList()
	{
		return _targetList;
	}

	public Expression getSource(){
		return _source;
	}
	public void setSource(Expression s){
		_source = s;
	}
	
	@Override
	public boolean controlStatement() {
		// ensure that breakpoints end up in own statement block 
		if (DMLScript.ENABLE_DEBUG_MODE) {
			DMLBreakpointManager.insertBreakpoint(_source.getBeginLine());
			return true;
		}

		// for now, ensure that function call ends up in different statement block
		if (_source instanceof FunctionCallIdentifier)
			return true;
		
		// for now, ensure that an assignment statement containing a read from csv ends up in own statement block
		if(_source != null && _source.toString().contains(DataExpression.FORMAT_TYPE + "=" + DataExpression.FORMAT_TYPE_VALUE_CSV) && _source.toString().contains("read"))
			return true;
		
		// ensure that specific ops end up in different statement block
		if( containsIndividualStatementBlockOperations() )
			return true;
		
		return false;
	}
	
	/**
	 * 
	 * @return
	 */
	public boolean containsIndividualStatementBlockOperations()
	{
		// if (DMLScript.ENABLE_DEBUG_MODE && !DMLScript.ENABLE_DEBUG_OPTIMIZER)
		if (DMLScript.ENABLE_DEBUG_MODE)
			return true;
		
		boolean ret = false;
		
		if( OptimizerUtils.ALLOW_INDIVIDUAL_SB_SPECIFIC_OPS )
		{
			//1) Leaf nodes with unknown size:
			//recompilation hook after reads with unknown size (this can currently only happen for csv)
			if( _source instanceof DataExpression && ((DataExpression)_source).isCSVReadWithUnknownSize() )
				ret = true;	
			
			//2) Data-dependent operations:
			
			//TODO additional candidates (currently, not enabled because worst-case estimates usually reasonable)
			//if( _source.toString().contains(Expression.DataOp.RAND.toString()) )
			//	ret = true;	
			//if( _source.toString().contains(Expression.ParameterizedBuiltinFunctionOp.GROUPEDAGG.toString()) )
			//	ret = true;	
			if( _source.toString().contains(Expression.ParameterizedBuiltinFunctionOp.RMEMPTY.toString()) )
				ret = true;	
			
			//recompilation hook after ctable because worst estimates usually too conservative 
			//(despite propagating worst-case estimates, especially if we not able to propagate sparsity)
			
			if( _source != null && _source.toString().contains(Expression.BuiltinFunctionOp.TABLE.toString()) 
				&& !isBuiltinCtableWithKnownDimensions(_source) ) //split only if unknown dimensions 
				ret = true;
		}
		//System.out.println(_source +": "+ret);
		
		return ret;
	}
	
	/**
	 * 
	 * @param expr
	 * @return
	 */
	private static boolean isBuiltinCtableWithKnownDimensions( Expression expr )
	{
		boolean ret = false;
		
		if( expr instanceof BuiltinFunctionExpression ){
			BuiltinFunctionExpression bexpr = (BuiltinFunctionExpression) expr;
			ret = (    bexpr.getOpCode() == Expression.BuiltinFunctionOp.TABLE
					&& bexpr.getAllExpr()!=null && bexpr.getAllExpr().length>3 );
		}
		
		return ret;
	}
	
	public void initializeforwardLV(VariableSet activeIn){
		//do nothing
	}
	
	public VariableSet initializebackwardLV(VariableSet lo){
		return lo;
	}
	
	public VariableSet variablesRead() {
		VariableSet result = new VariableSet();
		
		// add variables read by source expression
		result.addVariables(_source.variablesRead());
		
		// for LHS IndexedIdentifier, add variables for indexing expressions
		for (int i=0; i<_targetList.size(); i++){
			if (_targetList.get(i) instanceof IndexedIdentifier) {
				IndexedIdentifier target = (IndexedIdentifier) _targetList.get(i);
				result.addVariables(target.variablesRead());
			}
		}		
		return result;
	}
	
	public  VariableSet variablesUpdated() {
		VariableSet result =  new VariableSet();
		
		// add target to updated list
		for (DataIdentifier target : _targetList)
			result.addVariable(target.getName(), target);
		return result;
	}
	
	public String toString(){
		String retVal  = "";
		for (int i=0; i< _targetList.size(); i++){
			retVal += _targetList.get(i).toString();
		}
		retVal += " = " + _source.toString() + ";";
		return retVal;
	}
}
