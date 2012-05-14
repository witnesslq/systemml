package dml.parser;

import java.util.ArrayList;

import dml.utils.LanguageException;

public class IndexedIdentifier extends DataIdentifier {

	// stores the expressions containing the ranges for the 
	private Expression 	_rowLowerBound = null, _rowUpperBound = null, _colLowerBound = null, _colUpperBound = null;
	
	// stores whether row / col indices have same value (thus selecting either (1 X n) row-vector OR (n X 1) col-vector)
	private boolean _rowLowerEqualsUpper = false, _colLowerEqualsUpper = false;
	
	public IndexedIdentifier(String name){
		super(name);
		_rowLowerBound = null; 
   		_rowUpperBound = null; 
   		_colLowerBound = null; 
   		_colUpperBound = null;
   		_rowLowerEqualsUpper = false;
   		_colLowerEqualsUpper = false;
	}
		
	
	public void updateIndexedDimensions(){
		
		// stores the updated row / col dimension info
		long updatedRowDim = 1, updatedColDim = 1;
		
		///////////////////////////////////////////////////////////////////////
		// update row dimensions
		///////////////////////////////////////////////////////////////////////
			
		// CASE:  lower == upper --> updated row dim = 1
		if (_rowLowerEqualsUpper) 
			updatedRowDim = 1;
		
		// CASE: (lower == null || lower == const) && (upper == null || upper == const)
		//	--> 1) (lower == null) ? lower = 1 : lower = const; 
		//  --> 2) (upper == null) ? upper = current rows : lower 
		else if (_rowLowerBound == null && _rowUpperBound == null){
			updatedRowDim = this.getDim1(); 
		}
		else 
			updatedRowDim = -1;
		
		//////////////////////////////////////////////////////////////////////
		// update column dimensions
		///////////////////////////////////////////////////////////////////////
			
		// CASE:  lower == upper --> updated col dim = 1
		if (_colLowerEqualsUpper) 
			updatedColDim = 1;
		
		// CASE: (lower == null || lower == const) && (upper == null || upper == const)
		//	--> 1) (lower == null) ? lower = 1 : lower = const; 
		//  --> 2) (upper == null) ? upper = current_rows : upper - const 
		else if (_colLowerBound == null && _colUpperBound == null){
			updatedColDim = this.getDim2(); 
		}
		else 
			updatedColDim = -1;
		
		this.setDimensions(updatedRowDim, updatedColDim);
	}
	
	public Expression rewriteExpression(String prefix) throws LanguageException {
		
		IndexedIdentifier newIndexedIdentifier = new IndexedIdentifier(this.getName());
		newIndexedIdentifier.setProperties(this);
		
		newIndexedIdentifier._kind = Kind.Data;
		newIndexedIdentifier._name = prefix + this._name;
		newIndexedIdentifier._valueTypeString = this.getValueType().toString();	
		newIndexedIdentifier._defaultValue = this._defaultValue;
		
		newIndexedIdentifier._rowLowerBound = (_rowLowerBound == null) ? null : _rowLowerBound.rewriteExpression(prefix);
		newIndexedIdentifier._rowUpperBound = (_rowUpperBound == null) ? null : _rowUpperBound.rewriteExpression(prefix);
		newIndexedIdentifier._colLowerBound = (_colLowerBound == null) ? null : _colLowerBound.rewriteExpression(prefix);
		newIndexedIdentifier._colUpperBound = (_colUpperBound == null) ? null : _colUpperBound.rewriteExpression(prefix);
		
		return newIndexedIdentifier;
	}
		
	public void setIndices(ArrayList<ArrayList<Expression>> passed) throws ParseException {
		if (passed.size() != 2)
			throw new ParseException("[E] matrix indices must be specified for 2 dimensions -- currently specified indices for " + passed.size() + " dimensions ");
		
		ArrayList<Expression> rowIndices = passed.get(0);
		ArrayList<Expression> colIndices = passed.get(1);
	
		// case: both upper and lower are defined
		if (rowIndices.size() == 2){			
			_rowLowerBound = rowIndices.get(0);
			_rowUpperBound = rowIndices.get(1);
		}
		// case: only one index is defined --> thus lower = upper
		else if (rowIndices.size() == 1){
			_rowLowerBound = rowIndices.get(0);
			_rowUpperBound = rowIndices.get(0);
			_rowLowerEqualsUpper = true;
		}
		else {
			throw new ParseException("[E]  row indices are length " + rowIndices.size() + " -- should be either 1 or 2");
		}
		
		// case: both upper and lower are defined
		if (colIndices.size() == 2){			
			_colLowerBound = colIndices.get(0);
			_colUpperBound = colIndices.get(1);
		}
		// case: only one index is defined --> thus lower = upper
		else if (colIndices.size() == 1){
			_colLowerBound = colIndices.get(0);
			_colUpperBound = colIndices.get(0);
			_colLowerEqualsUpper = true;
		}
		else {
			throw new ParseException("[E] col indices are length " + + colIndices.size() + " -- should be either 1 or 2");
		}
		System.out.println(this);
	}
	
	public Expression getRowLowerBound(){ return this._rowLowerBound; }
	public Expression getRowUpperBound(){ return this._rowUpperBound; }
	public Expression getColLowerBound(){ return this._colLowerBound; }
	public Expression getColUpperBound(){ return this._colUpperBound; }
	
	public void setRowLowerBound(Expression passed){ this._rowLowerBound = passed; }
	public void setRowUpperBound(Expression passed){ this._rowUpperBound = passed; }
	public void setColLowerBound(Expression passed){ this._colLowerBound = passed; }
	public void setColUpperBound(Expression passed){ this._colUpperBound = passed; }
	
		
	public String toString() {
		String retVal = new String();
		retVal += this.getName();
		if (_rowLowerBound != null || _rowUpperBound != null || _colLowerBound != null || _colUpperBound != null){
				retVal += "[";
				
				if (_rowLowerBound != null && _rowUpperBound != null){
					if (_rowLowerBound.toString().equals(_rowUpperBound.toString()))
						retVal += _rowLowerBound.toString();
					else 
						retVal += _rowLowerBound.toString() + ":" + _rowUpperBound.toString();
				}
				else {
					if (_rowLowerBound != null)
						retVal += _rowLowerBound.toString();
					retVal += ":";
					if (_rowUpperBound != null)
						retVal += _rowUpperBound.toString();	
				}
					
				retVal += ",";
				
				if (_colLowerBound != null && _colUpperBound != null){
					if (_colLowerBound.toString().equals(_colUpperBound.toString()))
						retVal += _colLowerBound.toString();
					else
						retVal += _colLowerBound.toString() + ":" + _colUpperBound.toString();
				}
				else {
					if (_colLowerBound != null)
						retVal += _colLowerBound.toString();
					retVal += ":";
					if (_colUpperBound != null)
						retVal += _colUpperBound.toString();	
				}
					
				
				retVal += "]";
		}
		return retVal;
	}

	@Override
	public VariableSet variablesRead() {
		VariableSet result = new VariableSet();
		result.addVariable(this.getName(), this);
		
		if (_rowLowerBound != null)
			result.addVariables(_rowLowerBound.variablesRead());
		if (_rowUpperBound != null)
			result.addVariables(_rowUpperBound.variablesRead());
		if (_colLowerBound != null)
			result.addVariables(_colLowerBound.variablesRead());
		if (_colUpperBound != null)
			result.addVariables(_colUpperBound.variablesRead());
		
		return result;
	}
	
}
