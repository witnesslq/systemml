/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2015
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.hops.rewrite;

import java.util.ArrayList;
import java.util.HashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.ibm.bi.dml.hops.AggBinaryOp;
import com.ibm.bi.dml.hops.AggUnaryOp;
import com.ibm.bi.dml.hops.BinaryOp;
import com.ibm.bi.dml.hops.DataGenOp;
import com.ibm.bi.dml.hops.Hop;
import com.ibm.bi.dml.hops.Hop.OpOp1;
import com.ibm.bi.dml.hops.UnaryOp;
import com.ibm.bi.dml.hops.Hop.AggOp;
import com.ibm.bi.dml.hops.Hop.DataGenMethod;
import com.ibm.bi.dml.hops.Hop.Direction;
import com.ibm.bi.dml.hops.Hop.ParamBuiltinOp;
import com.ibm.bi.dml.hops.Hop.ReOrgOp;
import com.ibm.bi.dml.hops.HopsException;
import com.ibm.bi.dml.hops.LiteralOp;
import com.ibm.bi.dml.hops.Hop.OpOp2;
import com.ibm.bi.dml.hops.ParameterizedBuiltinOp;
import com.ibm.bi.dml.hops.ReorgOp;
import com.ibm.bi.dml.parser.DataExpression;
import com.ibm.bi.dml.parser.Statement;
import com.ibm.bi.dml.parser.Expression.DataType;
import com.ibm.bi.dml.parser.Expression.ValueType;

/**
 * Rule: Algebraic Simplifications. Simplifies binary expressions
 * in terms of two major purposes: (1) rewrite binary operations
 * to unary operations when possible (in CP this reduces the memory
 * estimate, in MR this allows map-only operations and hence prevents 
 * unnecessary shuffle and sort) and (2) remove binary operations that
 * are in itself are unnecessary (e.g., *1 and /1).
 * 
 */
public class RewriteAlgebraicSimplificationStatic extends HopRewriteRule
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2015\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	
	private static final Log LOG = LogFactory.getLog(RewriteAlgebraicSimplificationDynamic.class.getName());
	
	
	private static OpOp2[] LOOKUP_VALID_DISTRIBUTIVE_BINARY = new OpOp2[]{OpOp2.PLUS, OpOp2.MINUS}; 
	
	private static OpOp2[] LOOKUP_VALID_ASSOCIATIVE_BINARY = new OpOp2[]{OpOp2.PLUS, OpOp2.MULT}; 
	
	
	@Override
	public ArrayList<Hop> rewriteHopDAGs(ArrayList<Hop> roots, ProgramRewriteStatus state) 
		throws HopsException
	{
		if( roots == null )
			return roots;

		//one pass rewrite-descend (rewrite created pattern)
		for( Hop h : roots )
			rule_AlgebraicSimplification( h, false );

		Hop.resetVisitStatus(roots);
		
		//one pass descend-rewrite (for rollup) 
		for( Hop h : roots )
			rule_AlgebraicSimplification( h, true );
		
		return roots;
	}

	@Override
	public Hop rewriteHopDAG(Hop root, ProgramRewriteStatus state) 
		throws HopsException
	{
		if( root == null )
			return root;
		
		//one pass rewrite-descend (rewrite created pattern)
		rule_AlgebraicSimplification( root, false );

		root.resetVisitStatus();
		
		//one pass descend-rewrite (for rollup) 
		rule_AlgebraicSimplification( root, true );
		
		return root;
	}


	/**
	 * Note: X/y -> X * 1/y would be useful because * cheaper than / and sparsesafe; however,
	 * (1) the results would be not exactly the same (2 rounds instead of 1) and (2) it should 
	 * come before constant folding while the other simplifications should come after constant
	 * folding. Hence, not applied yet.
	 * 
	 * @throws HopsException
	 */
	private void rule_AlgebraicSimplification(Hop hop, boolean descendFirst) 
		throws HopsException 
	{
		if(hop.getVisited() == Hop.VisitStatus.DONE)
			return;
		
		//recursively process children
		for( int i=0; i<hop.getInput().size(); i++)
		{
			Hop hi = hop.getInput().get(i);
			
			//process childs recursively first (to allow roll-up)
			if( descendFirst )
				rule_AlgebraicSimplification(hi, descendFirst); //see below
			
			//apply actual simplification rewrites (of childs incl checks)
			hi = removeUnnecessaryVectorizeOperation(hi);        //e.g., matrix(1,nrow(X),ncol(X))/X -> 1/X
			hi = removeUnnecessaryBinaryOperation(hop, hi, i);   //e.g., X*1 -> X (dep: should come after rm unnecessary vectorize)
			hi = fuseDatagenAndBinaryOperation(hop, hi, i);      //e.g., rand(min=-1,max=1)*7 -> rand(min=-7,max=7)
			hi = fuseDatagenAndMinusOperation(hop, hi, i);       //e.g., -(rand(min=-2,max=1)) -> rand(min=-1,max=2)
 			hi = simplifyBinaryToUnaryOperation(hi);             //e.g., X*X -> X^2 (pow2)
 			hi = simplifyDistributiveBinaryOperation(hop, hi, i);//e.g., (X-Y*X) -> (1-Y)*X
 			hi = simplifyBushyBinaryOperation(hop, hi, i);       //e.g., (X*(Y*(Z%*%v))) -> (X*Y)*(Z%*%v)
			hi = fuseBinarySubDAGToUnaryOperation(hop, hi, i);   //e.g., X*(1-X)-> sprop(X)
			hi = simplifyTraceMatrixMult(hop, hi, i);            //e.g., trace(X%*%Y)->sum(X*t(Y));    
			hi = simplifyConstantSort(hop, hi, i);               //e.g., order(matrix())->matrix/seq; 
			hi = simplifyOrderedSort(hop, hi, i);                //e.g., order(matrix())->seq; 
			hi = removeUnnecessaryTranspose(hop, hi, i);         //e.g., t(t(X))->X; potentially introduced by diag/trace_MM
			hi = removeUnnecessaryMinus(hop, hi, i);             //e.g., -(-X)->X; potentially introduced by simplfiy binary or dyn rewrites
			hi = simplifyGroupedAggregate(hi);          	     //e.g., aggregate(target=X,groups=y,fn="count") -> aggregate(target=y,groups=y,fn="count")
			//hi = removeUnecessaryPPred(hop, hi, i);            //e.g., ppred(X,X,"==")->matrix(1,rows=nrow(X),cols=ncol(X))
			
			//process childs recursively after rewrites (to investigate pattern newly created by rewrites)
			if( !descendFirst )
				rule_AlgebraicSimplification(hi, descendFirst);
		}

		hop.setVisited(Hop.VisitStatus.DONE);
	}
	
	
	/**
	 * 
	 * @param hi
	 */
	private Hop removeUnnecessaryVectorizeOperation(Hop hi)
	{
		//applies to all binary matrix operations, if one input is unnecessarily vectorized 
		if(    hi instanceof BinaryOp && hi.getDataType()==DataType.MATRIX 
			&& ((BinaryOp)hi).supportsMatrixScalarOperations()               )
		{
			BinaryOp bop = (BinaryOp)hi;
			Hop left = bop.getInput().get(0);
			Hop right = bop.getInput().get(1);
			
			//NOTE: these rewrites of binary cell operations need to be aware that right is 
			//potentially a vector but the result is of the size of left
			
			//check and remove right vectorized scalar
			if( left.getDataType() == DataType.MATRIX && right instanceof DataGenOp )
			{
				DataGenOp dright = (DataGenOp) right;
				if( dright.getOp()==DataGenMethod.RAND && dright.hasConstantValue() )
				{
					Hop drightIn = dright.getInput().get(dright.getParamIndex(DataExpression.RAND_MIN));
					HopRewriteUtils.removeChildReference(bop, dright);
					HopRewriteUtils.addChildReference(bop, drightIn, 1);
					//cleanup if only consumer of intermediate
					if( dright.getParent().isEmpty() ) 
						HopRewriteUtils.removeAllChildReferences( dright );
					
					LOG.debug("Applied removeUnnecessaryVectorizeOperation1");
				}
			}
			//TODO move to dynamic rewrites (since size dependent to account for mv binary cell operations)
			//check and remove left vectorized scalar
			else if( right.getDataType() == DataType.MATRIX && left instanceof DataGenOp )
			{
				DataGenOp dleft = (DataGenOp) left;
				if( dleft.getOp()==DataGenMethod.RAND && dleft.hasConstantValue()
					&& (left.getDim2()==1 || right.getDim2()>1) 
					&& (left.getDim1()==1 || right.getDim1()>1))
				{
					Hop dleftIn = dleft.getInput().get(dleft.getParamIndex(DataExpression.RAND_MIN));
					HopRewriteUtils.removeChildReference(bop, dleft);
					HopRewriteUtils.addChildReference(bop, dleftIn, 0);
					//cleanup if only consumer of intermediate
					if( dleft.getParent().isEmpty() ) 
						HopRewriteUtils.removeAllChildReferences( dleft );
					
					LOG.debug("Applied removeUnnecessaryVectorizeOperation2");
				}
			}
			

			//Note: we applied this rewrite to at most one side in order to keep the
			//output semantically equivalent. However, future extensions might consider
			//to remove vectors from both side, compute the binary op on scalars and 
			//finally feed it into a datagenop of the original dimensions.
			
		}
		
		return hi;
	}
	
	
	/**
	 * handle removal of unnecessary binary operations
	 * 
	 * X/1 or X*1 or 1*X or X-0 -> X
	 * -1*X or X*-1-> -X		
	 * 
	 * @param parent
	 * @param hi
	 * @param pos
	 * @throws HopsException
	 */
	private Hop removeUnnecessaryBinaryOperation( Hop parent, Hop hi, int pos ) 
		throws HopsException
	{
		if( hi instanceof BinaryOp )
		{
			BinaryOp bop = (BinaryOp)hi;
			Hop left = bop.getInput().get(0);
			Hop right = bop.getInput().get(1);
			//X/1 or X*1 -> X 
			if(    left.getDataType()==DataType.MATRIX 
				&& right instanceof LiteralOp && ((LiteralOp)right).getDoubleValue()==1.0 )
			{
				if( bop.getOp()==OpOp2.DIV || bop.getOp()==OpOp2.MULT )
				{
					HopRewriteUtils.removeChildReference(parent, bop);
					HopRewriteUtils.addChildReference(parent, left, pos);
					hi = left;

					LOG.debug("Applied removeUnnecessaryBinaryOperation1");
				}
			}
			//X-0 -> X 
			else if(    left.getDataType()==DataType.MATRIX 
					&& right instanceof LiteralOp && ((LiteralOp)right).getDoubleValue()==0.0 )
			{
				if( bop.getOp()==OpOp2.MINUS )
				{
					HopRewriteUtils.removeChildReference(parent, bop);
					HopRewriteUtils.addChildReference(parent, left, pos);
					hi = left;

					LOG.debug("Applied removeUnnecessaryBinaryOperation2");
				}
			}
			//1*X -> X
			else if(   right.getDataType()==DataType.MATRIX 
					&& left instanceof LiteralOp && ((LiteralOp)left).getDoubleValue()==1.0 )
			{
				if( bop.getOp()==OpOp2.MULT )
				{
					HopRewriteUtils.removeChildReference(parent, bop);
					HopRewriteUtils.addChildReference(parent, right, pos);
					hi = right;

					LOG.debug("Applied removeUnnecessaryBinaryOperation3");
				}
			}
			//-1*X -> -X
			//note: this rewrite is necessary since the new antlr parser always converts 
			//-X to -1*X due to mechanical reasons
			else if(   right.getDataType()==DataType.MATRIX 
					&& left instanceof LiteralOp && ((LiteralOp)left).getDoubleValue()==-1.0 )
			{
				if( bop.getOp()==OpOp2.MULT )
				{
					bop.setOp(OpOp2.MINUS);
					HopRewriteUtils.removeChildReferenceByPos(bop, left, 0);
					HopRewriteUtils.addChildReference(bop, new LiteralOp("0",0), 0);
					hi = bop;

					LOG.debug("Applied removeUnnecessaryBinaryOperation4");
				}
			}
			//X*-1 -> -X (see comment above)
			else if(   left.getDataType()==DataType.MATRIX 
					&& right instanceof LiteralOp && ((LiteralOp)right).getDoubleValue()==-1.0 )
			{
				if( bop.getOp()==OpOp2.MULT )
				{
					bop.setOp(OpOp2.MINUS);
					HopRewriteUtils.removeChildReferenceByPos(bop, right, 1);
					HopRewriteUtils.addChildReference(bop, new LiteralOp("0",0), 0);
					hi = bop;
					
					LOG.debug("Applied removeUnnecessaryBinaryOperation5");
				}
			}
		}
		
		return hi;
	}
	
	/**
	 * handle removal of unnecessary binary operations over rand data
	 * 
	 * rand*7 -> rand(min*7,max*7); rand+7 -> rand(min+7,max+7);
	 * 
	 * @param parent
	 * @param hi
	 * @param pos
	 * @return
	 * @throws HopsException
	 */
	private Hop fuseDatagenAndBinaryOperation( Hop parent, Hop hi, int pos ) 
		throws HopsException
	{
		if( hi instanceof BinaryOp )
		{
			BinaryOp bop = (BinaryOp)hi;
			Hop left = bop.getInput().get(0);
			Hop right = bop.getInput().get(1);
			
			//left input rand and hence output matrix double, right scalar literal
			if( left instanceof DataGenOp && ((DataGenOp)left).getOp()==DataGenMethod.RAND &&
				right instanceof LiteralOp )
			{
				DataGenOp inputGen = (DataGenOp)left;
				HashMap<String,Integer> params = inputGen.getParamIndexMap();
				Hop min = left.getInput().get(params.get(DataExpression.RAND_MIN));
				Hop max = left.getInput().get(params.get(DataExpression.RAND_MAX));
				double sval = ((LiteralOp)right).getDoubleValue();
				
				if( (bop.getOp()==OpOp2.MULT || bop.getOp()==OpOp2.PLUS)
					&& min instanceof LiteralOp && max instanceof LiteralOp )
				{
					//create fused data gen operator
					DataGenOp gen = null;
					if( bop.getOp()==OpOp2.MULT )
						gen = HopRewriteUtils.copyDataGenOp(inputGen, sval, 0);
					else //if( bop.getOp()==OpOp2.PLUS )		
						gen = HopRewriteUtils.copyDataGenOp(inputGen, 1, sval);
						
					HopRewriteUtils.removeChildReference(parent, bop);
					HopRewriteUtils.addChildReference(parent, gen, pos);
					
					hi = gen;
					
					LOG.debug("Applied fuseDatagenAndBinaryOperation1");
				}
			}
			//right input rand and hence output matrix double, left scalar literal
			else if( right instanceof DataGenOp && ((DataGenOp)right).getOp()==DataGenMethod.RAND &&
				left instanceof LiteralOp )
			{
				DataGenOp inputGen = (DataGenOp)right;
				HashMap<String,Integer> params = inputGen.getParamIndexMap();
				Hop min = right.getInput().get(params.get(DataExpression.RAND_MIN));
				Hop max = right.getInput().get(params.get(DataExpression.RAND_MAX));
				double sval = ((LiteralOp)left).getDoubleValue();
				
				if( (bop.getOp()==OpOp2.MULT || bop.getOp()==OpOp2.PLUS)
					&& min instanceof LiteralOp && max instanceof LiteralOp )
				{
					//create fused data gen operator
					DataGenOp gen = null;
					if( bop.getOp()==OpOp2.MULT )
						gen = HopRewriteUtils.copyDataGenOp(inputGen, sval, 0);
					else //if( bop.getOp()==OpOp2.PLUS )		
						gen = HopRewriteUtils.copyDataGenOp(inputGen, 1, sval);
						
					HopRewriteUtils.removeChildReference(parent, bop);
					HopRewriteUtils.addChildReference(parent, gen, pos);
					
					hi = gen;
					
					LOG.debug("Applied fuseDatagenAndBinaryOperation2");
				}
			}
		}
		
		return hi;
	}
	
	/**
	 * 
	 * @param parent
	 * @param hi
	 * @param pos
	 * @return
	 * @throws HopsException
	 */
	private Hop fuseDatagenAndMinusOperation( Hop parent, Hop hi, int pos ) 
		throws HopsException
	{
		if( hi instanceof BinaryOp )
		{
			BinaryOp bop = (BinaryOp)hi;
			Hop left = bop.getInput().get(0);
			Hop right = bop.getInput().get(1);
			
			if( right instanceof DataGenOp && ((DataGenOp)right).getOp()==DataGenMethod.RAND &&
				left instanceof LiteralOp && ((LiteralOp)left).getDoubleValue()==0.0 )
			{
				DataGenOp inputGen = (DataGenOp)right;
				HashMap<String,Integer> params = inputGen.getParamIndexMap();
				int ixMin = params.get(DataExpression.RAND_MIN);
				int ixMax = params.get(DataExpression.RAND_MAX);
				Hop min = right.getInput().get(ixMin);
				Hop max = right.getInput().get(ixMax);
				
				//apply rewrite under additional conditions (for simplicity)
				if( inputGen.getParent().size()==1 
					&& min instanceof LiteralOp && max instanceof LiteralOp )
				{
					//exchange and *-1 (special case 0 stays 0 instead of -0 for consistency)
					double newMinVal = (((LiteralOp)max).getDoubleValue()==0)?0:(-1 * ((LiteralOp)max).getDoubleValue());
					double newMaxVal = (((LiteralOp)min).getDoubleValue()==0)?0:(-1 * ((LiteralOp)min).getDoubleValue());
					Hop newMin = new LiteralOp(String.valueOf(newMinVal), newMinVal);
					Hop newMax = new LiteralOp(String.valueOf(newMaxVal), newMaxVal);
					
					HopRewriteUtils.removeChildReferenceByPos(inputGen, min, ixMin);
					HopRewriteUtils.addChildReference(inputGen, newMin, ixMin);
					HopRewriteUtils.removeChildReferenceByPos(inputGen, max, ixMax);
					HopRewriteUtils.addChildReference(inputGen, newMax, ixMax);
					
					HopRewriteUtils.removeChildReference(parent, bop);
					HopRewriteUtils.addChildReference(parent, inputGen, pos);
					hi = inputGen;

					LOG.debug("Applied fuseDatagenAndMinusOperation");		
				}
			}
		}
		
		return hi;
	}
	
	/**
	 * handle simplification of binary operations
	 * (relies on previous common subexpression elimination)
	 * 
	 * X+X -> X*2 or X*X -> X^2
	 */
	private Hop simplifyBinaryToUnaryOperation( Hop hi )
	{
		if( hi instanceof BinaryOp )
		{
			BinaryOp bop = (BinaryOp)hi;
			Hop left = hi.getInput().get(0);
			Hop right = hi.getInput().get(1);
			if( left == right && left.getDataType()==DataType.MATRIX )
			{
				//note: we simplify this to unary operations first (less mem and better MR plan),
				//however, we later compile specific LOPS for X*2 and X^2
				if( bop.getOp()==OpOp2.PLUS ) //X+X -> X*2
				{
					bop.setOp(OpOp2.MULT);
					LiteralOp tmp = new LiteralOp("2", 2);
					bop.getInput().remove(1);
					right.getParent().remove(bop);
					HopRewriteUtils.addChildReference(hi, tmp, 1);

					LOG.debug("Applied simplifyBinaryToUnaryOperation1");
				}
				else if ( bop.getOp()==OpOp2.MULT ) //X*X -> X^2
				{
					bop.setOp(OpOp2.POW);
					LiteralOp tmp = new LiteralOp("2", 2);
					bop.getInput().remove(1);
					right.getParent().remove(bop);
					HopRewriteUtils.addChildReference(hi, tmp, 1);
					
					LOG.debug("Applied simplifyBinaryToUnaryOperation2");
				}
			}
		}
		
		return hi;
	}
	
	/**
	 * (X-Y*X) -> (1-Y)*X,    (Y*X-X) -> (Y-1)*X
	 * (X+Y*X) -> (1+Y)*X,    (Y*X+X) -> (Y+1)*X
	 * 
	 * 
	 * @param parent
	 * @param hi
	 * @param pos
	 * @return
	 */
	private Hop simplifyDistributiveBinaryOperation( Hop parent, Hop hi, int pos )
	{
		
		if( hi instanceof BinaryOp )
		{
			BinaryOp bop = (BinaryOp)hi;
			Hop left = bop.getInput().get(0);
			Hop right = bop.getInput().get(1);
			
			//(X+Y*X) -> (1+Y)*X,    (Y*X+X) -> (Y+1)*X
			//(X-Y*X) -> (1-Y)*X,    (Y*X-X) -> (Y-1)*X
			boolean applied = false;
			if( left.getDataType()==DataType.MATRIX && right.getDataType()==DataType.MATRIX 
				&& HopRewriteUtils.isValidOp(bop.getOp(), LOOKUP_VALID_DISTRIBUTIVE_BINARY) )
			{
				Hop X = null; Hop Y = null;
				if( left instanceof BinaryOp && ((BinaryOp)left).getOp()==OpOp2.MULT ) //(Y*X-X) -> (Y-1)*X
				{
					Hop leftC1 = left.getInput().get(0);
					Hop leftC2 = left.getInput().get(1);
					//System.out.println("aOp2:"+((BinaryOp)left).getOp()+": "+leftC1.getName()+" "+leftC2.getName());
						
					if( leftC1.getDataType()==DataType.MATRIX && leftC2.getDataType()==DataType.MATRIX &&
						(right == leftC1 || right == leftC2) && leftC1 !=leftC2 ){ //any mult order
						X = right;
						Y = ( right == leftC1 ) ? leftC2 : leftC1;
					}
					if( X != null ){ //rewrite 'binary +/-' 
						HopRewriteUtils.removeChildReference(parent, hi);
						LiteralOp literal = new LiteralOp("1",1);
						BinaryOp plus = new BinaryOp(right.getName(), right.getDataType(), right.getValueType(), bop.getOp(), Y, literal);
						HopRewriteUtils.refreshOutputParameters(plus, right);						
						BinaryOp mult = new BinaryOp(left.getName(), left.getDataType(), left.getValueType(), OpOp2.MULT, plus, X);
						HopRewriteUtils.refreshOutputParameters(mult, left);
						
						HopRewriteUtils.addChildReference(parent, mult, pos);							
						hi = mult;
						applied = true;
						
						LOG.debug("Applied simplifyDistributiveBinaryOperation1");
					}					
				}	
				
				if( !applied && right instanceof BinaryOp && ((BinaryOp)right).getOp()==OpOp2.MULT ) //(X-Y*X) -> (1-Y)*X
				{
					Hop rightC1 = right.getInput().get(0);
					Hop rightC2 = right.getInput().get(1);
					if( rightC1.getDataType()==DataType.MATRIX && rightC2.getDataType()==DataType.MATRIX &&
						(left == rightC1 || left == rightC2) && rightC1 !=rightC2 ){ //any mult order
						X = left;
						Y = ( left == rightC1 ) ? rightC2 : rightC1;
					}
					if( X != null ){ //rewrite '+/- binary'
						HopRewriteUtils.removeChildReference(parent, hi);
						LiteralOp literal = new LiteralOp("1",1);
						BinaryOp plus = new BinaryOp(left.getName(), left.getDataType(), left.getValueType(), bop.getOp(), literal, Y);
						HopRewriteUtils.refreshOutputParameters(plus, left);						
						BinaryOp mult = new BinaryOp(right.getName(), right.getDataType(), right.getValueType(), OpOp2.MULT, plus, X);
						HopRewriteUtils.refreshOutputParameters(mult, right);
						
						HopRewriteUtils.addChildReference(parent, mult, pos);	
						hi = mult;

						LOG.debug("Applied simplifyDistributiveBinaryOperation2");
					}
				}	
			}
		}
		
		return hi;
	}
	
	/**
	 * (X*(Y*(Z%*%v))) -> (X*Y)*(Z%*%v)
	 * (X+(Y+(Z%*%v))) -> (X+Y)+(Z%*%v)
	 * 
	 * Note: Restriction ba() at leaf and root instead of data at leaf to not reorganize too
	 * eagerly, which would loose additional rewrite potential. This rewrite has two goals
	 * (1) enable XtwXv, and increase piggybacking potential by creating bushy trees.
	 * 
	 * @param parent
	 * @param hi
	 * @param pos
	 * @return
	 */
	private Hop simplifyBushyBinaryOperation( Hop parent, Hop hi, int pos )
	{
		if( hi instanceof BinaryOp && parent instanceof AggBinaryOp )
		{
			BinaryOp bop = (BinaryOp)hi;
			Hop left = bop.getInput().get(0);
			Hop right = bop.getInput().get(1);
			OpOp2 op = bop.getOp();
			
			if( left.getDataType()==DataType.MATRIX && right.getDataType()==DataType.MATRIX &&
				HopRewriteUtils.isValidOp(op, LOOKUP_VALID_ASSOCIATIVE_BINARY) )
			{
				boolean applied = false;
				
				if( right instanceof BinaryOp )
				{
					BinaryOp bop2 = (BinaryOp)right;
					Hop left2 = bop2.getInput().get(0);
					Hop right2 = bop2.getInput().get(1);
					OpOp2 op2 = bop2.getOp();
					
					if( op==op2 && right2.getDataType()==DataType.MATRIX 
						&& (right2 instanceof AggBinaryOp) )
					{
						//(X*(Y*op()) -> (X*Y)*op()
						HopRewriteUtils.removeChildReference(parent, bop);
						
						BinaryOp bop3 = new BinaryOp("tmp1", DataType.MATRIX, ValueType.DOUBLE, op, left, left2);
						HopRewriteUtils.refreshOutputParameters(bop3, bop);
						BinaryOp bop4 = new BinaryOp("tmp2", DataType.MATRIX, ValueType.DOUBLE, op, bop3, right2);
						HopRewriteUtils.refreshOutputParameters(bop4, bop2);
						
						HopRewriteUtils.addChildReference(parent, bop4, pos);	
						hi = bop4;
						
						applied = true;
						
						LOG.debug("Applied simplifyBushyBinaryOperation1");
					}
				}
				
				if( !applied && left instanceof BinaryOp )
				{
					BinaryOp bop2 = (BinaryOp)left;
					Hop left2 = bop2.getInput().get(0);
					Hop right2 = bop2.getInput().get(1);
					OpOp2 op2 = bop2.getOp();
					
					if( op==op2 && left2.getDataType()==DataType.MATRIX 
						&& (left2 instanceof AggBinaryOp) 
						&& (right2.getDim2() > 1 || right.getDim2() == 1)   //X not vector, or Y vector
						&& (right2.getDim1() > 1 || right.getDim1() == 1) ) //X not vector, or Y vector
					{
						//((op()*X)*Y) -> op()*(X*Y)
						HopRewriteUtils.removeChildReference(parent, bop);
						
						BinaryOp bop3 = new BinaryOp("tmp1", DataType.MATRIX, ValueType.DOUBLE, op, right2, right);
						HopRewriteUtils.refreshOutputParameters(bop3, bop2);
						BinaryOp bop4 = new BinaryOp("tmp2", DataType.MATRIX, ValueType.DOUBLE, op, left2, bop3);
						HopRewriteUtils.refreshOutputParameters(bop4, bop);
						
						HopRewriteUtils.addChildReference(parent, bop4, pos);	
						hi = bop4;
						
						LOG.debug("Applied simplifyBushyBinaryOperation2");
					}
				}
			}
			
		}
		
		return hi;
	}
	
	/**
	 * handle simplification of more complex sub DAG to unary operation.
	 * 
	 * X*(1-X) -> sprop(X)
	 * (1-X)*X -> sprop(X)
	 * 
	 * @param hi
	 * @throws HopsException 
	 */
	private Hop fuseBinarySubDAGToUnaryOperation( Hop parent, Hop hi, int pos ) 
		throws HopsException
	{
		if( hi instanceof BinaryOp )
		{
			BinaryOp bop = (BinaryOp)hi;
			Hop left = hi.getInput().get(0);
			Hop right = hi.getInput().get(1);
			
			if( bop.getOp() == OpOp2.MULT && left.getDataType()==DataType.MATRIX && right.getDataType()==DataType.MATRIX )
			{
				//by definition, either left or right or none applies. 
				//note: if there are multiple consumers on the intermediate,
				//we follow the heuristic that redundant computation is more beneficial, 
				//i.e., we still fuse but leave the intermediate for the other consumers  
				
				if( left instanceof BinaryOp ) //(1-X)*X
				{
					BinaryOp bleft = (BinaryOp)left;
					Hop left1 = bleft.getInput().get(0);
					Hop left2 = bleft.getInput().get(1);		
				
					if( left1 instanceof LiteralOp &&
						HopRewriteUtils.getDoubleValue((LiteralOp)left1)==1 &&	
						left2 == right &&
						bleft.getOp() == OpOp2.MINUS  ) 
					{
						UnaryOp unary = new UnaryOp(bop.getName(), bop.getDataType(), bop.getValueType(), OpOp1.SPROP, right);
						HopRewriteUtils.setOutputBlocksizes(unary, bop.getRowsInBlock(), bop.getColsInBlock());
						HopRewriteUtils.copyLineNumbers(bop, unary);
						unary.refreshSizeInformation();
						
						HopRewriteUtils.removeChildReferenceByPos(parent, bop, pos);
						HopRewriteUtils.addChildReference(parent, unary, pos);
						
						//cleanup if only consumer of intermediate
						if( bop.getParent().isEmpty() )
							HopRewriteUtils.removeAllChildReferences(bop);					
						if( left.getParent().isEmpty() ) 
							HopRewriteUtils.removeAllChildReferences(left);
						
						hi = unary;
						
						LOG.debug("Applied fuseBinarySubDAGToUnaryOperation1");
					}
				}				
				if( right instanceof BinaryOp ) //X*(1-X)
				{
					BinaryOp bright = (BinaryOp)right;
					Hop right1 = bright.getInput().get(0);
					Hop right2 = bright.getInput().get(1);		
				
					if( right1 instanceof LiteralOp &&
						HopRewriteUtils.getDoubleValue((LiteralOp)right1)==1 &&	
						right2 == left &&
						bright.getOp() == OpOp2.MINUS )
					{
						UnaryOp unary = new UnaryOp(bop.getName(), bop.getDataType(), bop.getValueType(), OpOp1.SPROP, left);
						HopRewriteUtils.setOutputBlocksizes(unary, bop.getRowsInBlock(), bop.getColsInBlock());
						HopRewriteUtils.copyLineNumbers(bop, unary);
						unary.refreshSizeInformation();
						
						HopRewriteUtils.removeChildReferenceByPos(parent, bop, pos);
						HopRewriteUtils.addChildReference(parent, unary, pos);
						
						//cleanup if only consumer of intermediate
						if( bop.getParent().isEmpty() )
							HopRewriteUtils.removeAllChildReferences(bop);					
						if( left.getParent().isEmpty() ) 
							HopRewriteUtils.removeAllChildReferences(right);
						
						hi = unary;
						
						LOG.debug("Applied fuseBinarySubDAGToUnaryOperation2");
					}
				}
			}
			
		}
		
		return hi;
	}
	
	
	/**
	 * 
	 * @param parent
	 * @param hi
	 * @param pos
	 * @return
	 */
	private Hop simplifyTraceMatrixMult(Hop parent, Hop hi, int pos)
	{
		if( hi instanceof AggUnaryOp && ((AggUnaryOp)hi).getOp()==AggOp.TRACE ) //trace()
		{
			Hop hi2 = hi.getInput().get(0);
			if( hi2 instanceof AggBinaryOp && ((AggBinaryOp)hi2).isMatrixMultiply() ) //X%*%Y
			{
				Hop left = hi2.getInput().get(0);
				Hop right = hi2.getInput().get(1);
				
				//remove link from parent to diag
				HopRewriteUtils.removeChildReference(parent, hi);
				
				//remove links to inputs to matrix mult
				//removeChildReference(hi2, left);
				//removeChildReference(hi2, right);
				
				//create new operators (incl refresh size inside for transpose)
				ReorgOp trans = new ReorgOp(right.getName(), right.getDataType(), right.getValueType(), ReOrgOp.TRANSPOSE, right);
				trans.setRowsInBlock(right.getRowsInBlock());
				trans.setColsInBlock(right.getColsInBlock());
				BinaryOp mult = new BinaryOp(right.getName(), right.getDataType(), right.getValueType(), OpOp2.MULT, left, trans);
				mult.setRowsInBlock(right.getRowsInBlock());
				mult.setColsInBlock(right.getColsInBlock());
				mult.refreshSizeInformation();
				AggUnaryOp sum = new AggUnaryOp(right.getName(), DataType.SCALAR, right.getValueType(), AggOp.SUM, Direction.RowCol, mult);
				sum.refreshSizeInformation();
				
				//rehang new subdag under parent node
				HopRewriteUtils.addChildReference(parent, sum, pos);
				
				//cleanup if only consumer of intermediate
				if( hi.getParent().isEmpty() ) 
					HopRewriteUtils.removeAllChildReferences( hi );
				if( hi2.getParent().isEmpty() ) 
					HopRewriteUtils.removeAllChildReferences( hi2 );
				
				hi = sum;
				
				LOG.debug("Applied simplifyTraceMatrixMult");
			}	
		}
		
		return hi;
	}
	
	/**
	 * 
	 * @param parent
	 * @param hi
	 * @param pos
	 * @return
	 * @throws HopsException
	 */
	private Hop simplifyConstantSort(Hop parent, Hop hi, int pos) 
		throws HopsException
	{
		//order(matrix(7), indexreturn=FALSE) -> matrix(7)
		//order(matrix(7), indexreturn=TRUE) -> seq(1,nrow(X),1)
		if( hi instanceof ReorgOp && ((ReorgOp)hi).getOp()==ReOrgOp.SORT )  //order
		{
			Hop hi2 = hi.getInput().get(0);
			
			if( hi2 instanceof DataGenOp && ((DataGenOp)hi2).getOp()==DataGenMethod.RAND
				&& ((DataGenOp)hi2).hasConstantValue() 
				&& hi.getInput().get(3) instanceof LiteralOp ) //known indexreturn
			{
				if( HopRewriteUtils.getBooleanValue((LiteralOp)hi.getInput().get(3)) )
				{
					//order(matrix(7), indexreturn=TRUE) -> seq(1,nrow(X),1)
					HopRewriteUtils.removeChildReferenceByPos(parent, hi, pos);
					Hop seq = HopRewriteUtils.createSeqDataGenOp(hi2);
					seq.refreshSizeInformation();
					HopRewriteUtils.addChildReference(parent, seq, pos);
					if( hi.getParent().isEmpty() )
						HopRewriteUtils.removeChildReference(hi, hi2);
					hi = seq;
					
					LOG.debug("Applied simplifyConstantSort1.");
				}
				else
				{
					//order(matrix(7), indexreturn=FALSE) -> matrix(7)
					HopRewriteUtils.removeChildReferenceByPos(parent, hi, pos);
					HopRewriteUtils.addChildReference(parent, hi2, pos);
					if( hi.getParent().isEmpty() )
						HopRewriteUtils.removeChildReference(hi, hi2);
					hi = hi2;
					
					LOG.debug("Applied simplifyConstantSort2.");
				}
			}	
		}
		
		return hi;
	}
	
	private Hop simplifyOrderedSort(Hop parent, Hop hi, int pos) 
		throws HopsException
	{
		//order(seq(2,N+1,1), indexreturn=FALSE) -> matrix(7)
		//order(seq(2,N+1,1), indexreturn=TRUE) -> seq(1,N,1)/seq(N,1,-1)
		if( hi instanceof ReorgOp && ((ReorgOp)hi).getOp()==ReOrgOp.SORT )  //order
		{
			Hop hi2 = hi.getInput().get(0);
			
			if( hi2 instanceof DataGenOp && ((DataGenOp)hi2).getOp()==DataGenMethod.SEQ )
			{
				Hop incr = hi2.getInput().get(((DataGenOp)hi2).getParamIndex(Statement.SEQ_INCR));
				//check for known ascending ordering and known indexreturn
				if( incr instanceof LiteralOp && HopRewriteUtils.getDoubleValue((LiteralOp)incr)==1
					&& hi.getInput().get(2) instanceof LiteralOp      //decreasing
					&& hi.getInput().get(3) instanceof LiteralOp )    //indexreturn
				{
					if( HopRewriteUtils.getBooleanValue((LiteralOp)hi.getInput().get(3)) ) //IXRET, ASC/DESC
					{
						//order(seq(2,N+1,1), indexreturn=TRUE) -> seq(1,N,1)/seq(N,1,-1)
						boolean desc = HopRewriteUtils.getBooleanValue((LiteralOp)hi.getInput().get(2));
						HopRewriteUtils.removeChildReferenceByPos(parent, hi, pos);
						Hop seq = HopRewriteUtils.createSeqDataGenOp(hi2, !desc);
						seq.refreshSizeInformation();
						HopRewriteUtils.addChildReference(parent, seq, pos);
						if( hi.getParent().isEmpty() )
							HopRewriteUtils.removeChildReference(hi, hi2);
						hi = seq;
						
						LOG.debug("Applied simplifyOrderedSort1.");
					}
					else if( !HopRewriteUtils.getBooleanValue((LiteralOp)hi.getInput().get(2)) ) //DATA, ASC
					{
						//order(seq(2,N+1,1), indexreturn=FALSE) -> seq(2,N+1,1)
						HopRewriteUtils.removeChildReferenceByPos(parent, hi, pos);
						HopRewriteUtils.addChildReference(parent, hi2, pos);
						if( hi.getParent().isEmpty() )
							HopRewriteUtils.removeChildReference(hi, hi2);
						hi = hi2;
						
						LOG.debug("Applied simplifyOrderedSort2.");
					}
				}
			}	   
		}
		
		return hi;
	}
	
	/**
	 * 
	 * @param parent
	 * @param hi
	 * @param pos
	 */
	private Hop removeUnnecessaryTranspose(Hop parent, Hop hi, int pos)
	{
		if( hi instanceof ReorgOp && ((ReorgOp)hi).getOp()==ReOrgOp.TRANSPOSE  ) //first transpose
		{
			Hop hi2 = hi.getInput().get(0);
			if( hi2 instanceof ReorgOp && ((ReorgOp)hi2).getOp()==ReOrgOp.TRANSPOSE ) //second transpose
			{
				Hop hi3 = hi2.getInput().get(0);
				//remove unnecessary chain of t(t())
				HopRewriteUtils.removeChildReference(parent, hi);
				HopRewriteUtils.addChildReference(parent, hi3, pos);
				hi = hi3;
				
				//cleanup if only consumer of intermediate
				if( hi.getParent().isEmpty() ) 
					HopRewriteUtils.removeAllChildReferences( hi );
				if( hi2.getParent().isEmpty() ) 
					HopRewriteUtils.removeAllChildReferences( hi2 );
				
				LOG.debug("Applied removeUnecessaryTranspose");
			}
		}
		
		return hi;
	}
	
	/**
	 * 
	 * @param parent
	 * @param hi
	 * @param pos
	 * @return
	 * @throws HopsException 
	 */
	private Hop removeUnnecessaryMinus(Hop parent, Hop hi, int pos) 
		throws HopsException
	{
		if( hi.getDataType() == DataType.MATRIX && hi instanceof BinaryOp 
			&& ((BinaryOp)hi).getOp()==OpOp2.MINUS  						//first minus
			&& hi.getInput().get(0) instanceof LiteralOp && ((LiteralOp)hi.getInput().get(0)).getDoubleValue()==0 )
		{
			Hop hi2 = hi.getInput().get(1);
			if( hi2.getDataType() == DataType.MATRIX && hi2 instanceof BinaryOp 
				&& ((BinaryOp)hi2).getOp()==OpOp2.MINUS  						//second minus
				&& hi2.getInput().get(0) instanceof LiteralOp && ((LiteralOp)hi2.getInput().get(0)).getDoubleValue()==0 )
				
			{
				Hop hi3 = hi2.getInput().get(1);
				//remove unnecessary chain of -(-())
				HopRewriteUtils.removeChildReference(parent, hi);
				HopRewriteUtils.addChildReference(parent, hi3, pos);
				hi = hi3;
				
				//cleanup if only consumer of intermediate
				if( hi.getParent().isEmpty() ) 
					HopRewriteUtils.removeAllChildReferences( hi );
				if( hi2.getParent().isEmpty() ) 
					HopRewriteUtils.removeAllChildReferences( hi2 );
				
				LOG.debug("Applied removeUnecessaryMinus");
			}
		}
		
		return hi;
	}
	
	/**
	 * 
	 * @param hi
	 * @return
	 */
	private Hop simplifyGroupedAggregate(Hop hi)
	{
		if( hi instanceof ParameterizedBuiltinOp && ((ParameterizedBuiltinOp)hi).getOp()==ParamBuiltinOp.GROUPEDAGG  ) //aggregate
		{
			ParameterizedBuiltinOp phi = (ParameterizedBuiltinOp)hi;
			
			if( phi.isCountFunction() ) //aggregate(fn="count")
			{
				HashMap<String, Integer> params = phi.getParamIndexMap();
				int ix1 = params.get(Statement.GAGG_TARGET);
				int ix2 = params.get(Statement.GAGG_GROUPS);
				
				//check for unnecessary memory consumption for "count"
				if( ix1 != ix2 && phi.getInput().get(ix1)!=phi.getInput().get(ix2) ) 
				{
					Hop th = phi.getInput().get(ix1);
					Hop gh = phi.getInput().get(ix2);
					
					HopRewriteUtils.removeChildReference(hi, th);
					HopRewriteUtils.addChildReference(hi, gh, ix1);
					
					LOG.debug("Applied simplifyGroupedAggregateCount");	
				}
			}
		}
		
		return hi;
	}
	
	
	/**
	 * NOTE: currently disabled since this rewrite is INVALID in the
	 * presence of NaNs (because (NaN!=NaN) is true). 
	 * 
	 * @param parent
	 * @param hi
	 * @param pos
	 * @return
	 * @throws HopsException
	 */
	@SuppressWarnings("unused")
	private Hop removeUnecessaryPPred(Hop parent, Hop hi, int pos) 
		throws HopsException
	{
		if( hi instanceof BinaryOp )
		{
			BinaryOp bop = (BinaryOp)hi;
			Hop left = bop.getInput().get(0);
			Hop right = bop.getInput().get(1);
			
			Hop datagen = null;
			
			//ppred(X,X,"==") -> matrix(1, rows=nrow(X),cols=nrow(Y))
			if( left==right && bop.getOp()==OpOp2.EQUAL || bop.getOp()==OpOp2.GREATEREQUAL || bop.getOp()==OpOp2.LESSEQUAL )
				datagen = HopRewriteUtils.createDataGenOp(left, 1);
			
			//ppred(X,X,"!=") -> matrix(0, rows=nrow(X),cols=nrow(Y))
			if( left==right && bop.getOp()==OpOp2.NOTEQUAL || bop.getOp()==OpOp2.GREATER || bop.getOp()==OpOp2.LESS )
				datagen = HopRewriteUtils.createDataGenOp(left, 0);
					
			if( datagen != null )
			{
				HopRewriteUtils.removeChildReference(parent, hi);
				HopRewriteUtils.addChildReference(parent, datagen, pos);
				hi = datagen;
			}
		}
		
		return hi;
	}
	
}