package com.ibm.bi.dml.runtime.controlprogram.caching;

import java.io.File;
import java.io.IOException;
import java.lang.ref.SoftReference;

import com.ibm.bi.dml.api.DMLScript;
import com.ibm.bi.dml.lops.Lops;
import com.ibm.bi.dml.parser.DMLTranslator;
import com.ibm.bi.dml.parser.Expression.DataType;
import com.ibm.bi.dml.parser.Expression.ValueType;
import com.ibm.bi.dml.runtime.controlprogram.ParForProgramBlock.PDataPartitionFormat;
import com.ibm.bi.dml.runtime.instructions.MRInstructions.RangeBasedReIndexInstruction.IndexRange;
import com.ibm.bi.dml.runtime.matrix.MatrixCharacteristics;
import com.ibm.bi.dml.runtime.matrix.MatrixDimensionsMetaData;
import com.ibm.bi.dml.runtime.matrix.MatrixFormatMetaData;
import com.ibm.bi.dml.runtime.matrix.MetaData;
import com.ibm.bi.dml.runtime.matrix.io.InputInfo;
import com.ibm.bi.dml.runtime.matrix.io.MatrixBlock;
import com.ibm.bi.dml.runtime.matrix.io.NumItemsByEachReducerMetaData;
import com.ibm.bi.dml.runtime.matrix.io.OutputInfo;
import com.ibm.bi.dml.runtime.util.DataConverter;
import com.ibm.bi.dml.runtime.util.LocalFileUtils;
import com.ibm.bi.dml.runtime.util.MapReduceTool;
import com.ibm.bi.dml.utils.CacheAssignmentException;
import com.ibm.bi.dml.utils.CacheException;
import com.ibm.bi.dml.utils.CacheIOException;
import com.ibm.bi.dml.utils.CacheStatusException;
import com.ibm.bi.dml.utils.DMLRuntimeException;


/**
 * Represents a matrix in control program. This class contains method to read
 * matrices from HDFS and convert them to a specific format/representation. It
 * is also able to write several formats/representation of matrices to HDFS.

 * IMPORTANT: Preserve one-to-one correspondence between {@link MatrixObject}
 * and {@link MatrixBlock} objects, for cache purposes.  Do not change a
 * {@link MatrixBlock} object without informing its {@link MatrixObject} object.
 * 
 */
public class MatrixObject extends CacheableData
{
	/**
	 * Cache for actual data, evicted by garbage collector.
	 */
	private SoftReference<MatrixBlock> _cache = null;

	/**
	 * Container object that holds the actual data.
	 */
	private MatrixBlock _data = null;

	/**
	 * The name of HDFS file in which the data is backed up.
	 */
	private String _hdfsFileName = null; // file name and path
	
	/** 
	 * Flag that indicates whether or not hdfs file exists.
	 * It is used for improving the performance of "rmvar" instruction.
	 * When it has value <code>false</code>, one can skip invocations to
	 * utility functions such as MapReduceTool.deleteFileIfExistOnHDFS(),
	 * which can be potentially expensive.
	 */
	private boolean _hdfsFileExists = false; 
	
	/**
	 * <code>true</code> if the in-memory or evicted matrix may be different from
	 * the matrix located at {@link #_hdfsFileName}; <code>false</code> if the two
	 * matrices should be the same.
	 */
	private boolean _dirtyFlag = false;
	
	/**
	 * Object that holds the metadata associated with the matrix, which
	 * includes: 1) Matrix dimensions, if available 2) Number of non-zeros, if
	 * available 3) Block dimensions, if applicable 4) InputInfo -- subsequent
	 * operations that use this Matrix expect it to be in this format.
	 * 
	 * When the matrix is written to HDFS (local file system, as well?), one
	 * must get the OutputInfo that matches with InputInfo stored inside _mtd.
	 */
	private MetaData _metaData = null;
	
	//additional names and flags
	private String _varName = ""; //plan variable name
	private String _cacheFileName = null; //local eviction file name
	private boolean _requiresLocalWrite = false; //flag if local write for read obj
	private boolean _cleanupFlag = true; //flag if obj unpinned
	
	//partitioning information
	private boolean _partitioned = false; //indicates if obj partitioned
	private PDataPartitionFormat _partitionFormat = null; //indicates how obj partitioned
	private String _partitionCacheName = null; //name of cache block


	/**
	 * Constructor that takes only the HDFS filename.
	 */
	public MatrixObject (ValueType vt, String file)
	{
		this (vt, file, null); //HDFS file path
	}
	
	/**
	 * Constructor that takes both HDFS filename and associated metadata.
	 */
	public MatrixObject (ValueType vt, String file, MetaData mtd)
	{
		super (DataType.MATRIX, vt);
		_metaData = mtd; 
		_hdfsFileName = file;
		
		_cache = null;
		_data = null;
	}

	public void setVarName(String s) 
	{
		_varName = s;
	}
	
	public String getVarName() 
	{
		return _varName;
	}
	
	@Override
	public void setMetaData(MetaData md)
	{
		_metaData = md;
	}
	
	@Override
	public MetaData getMetaData()
	{
		return _metaData;
	}

	@Override
	public void removeMetaData()
	{
		_metaData = null;
	}
	
	@Override
	public void updateMatrixCharacteristics (MatrixCharacteristics mc)
	{
		((MatrixDimensionsMetaData)_metaData).setMatrixCharacteristics( mc );
	}

	/**
	 * Make the matrix metadata consistent with the in-memory matrix data
	 * @throws CacheException 
	 */
	public void refreshMetaData() 
		throws CacheException
	{
		if ( _data == null || _metaData ==null ) //refresh only for existing data
			throw new CacheException("Cannot refresh meta data because there is no data or meta data. "); 
		    //we need to throw an exception, otherwise input/output format cannot be inferred
		
		MatrixCharacteristics mc = ((MatrixDimensionsMetaData) _metaData).getMatrixCharacteristics();
		mc.setDimension( _data.getNumRows(),
						 _data.getNumColumns() );
		mc.setNonZeros( _data.getNonZeros() );		
	}

	public boolean isFileExists() 
	{
		return _hdfsFileExists;
	}
	
	public void setFileExists( boolean flag ) 
	{
		_hdfsFileExists = false;
	}
	
	public String getFileName()
	{
		return _hdfsFileName;
	}

	public void setFileName( String file )
	{
		if (!_hdfsFileName.equals (file))
		{
			_hdfsFileName = file;
			if (! isEmpty ())
				_dirtyFlag = true;
		}
	}

	public long getNumRows () 
		throws DMLRuntimeException
	{
		if(_metaData == null)
			throw new DMLRuntimeException("No metadata available.");
		MatrixCharacteristics mc = ((MatrixDimensionsMetaData) _metaData).getMatrixCharacteristics();
		return mc.get_rows ();
	}

	public long getNumColumns() 
		throws DMLRuntimeException
	{
		if(_metaData == null)
			throw new DMLRuntimeException("No metadata available.");
		MatrixCharacteristics mc = ((MatrixDimensionsMetaData) _metaData).getMatrixCharacteristics();
		return mc.get_cols ();
	}

	public long getNnz() 
		throws DMLRuntimeException
	{
		if(_metaData == null)
			throw new DMLRuntimeException("No metadata available.");
		MatrixCharacteristics mc = ((MatrixDimensionsMetaData) _metaData).getMatrixCharacteristics();
		return mc.nonZero;
	}

	/**
	 * <code>true</code> if the in-memory or evicted matrix may be different from
	 * the matrix located at {@link #_hdfsFileName}; <code>false</code> if the two
	 * matrices are supposed to be the same.
	 */
	public boolean isDirty ()
	{
		return _dirtyFlag;
	}
	
	public String toString()
	{ 
		StringBuilder str = new StringBuilder();
		str.append("Matrix: ");
		str.append(_hdfsFileName + ", ");
		//System.out.println(_hdfsFileName);
		if ( _metaData instanceof NumItemsByEachReducerMetaData ) {
			str.append("NumItemsByEachReducerMetaData");
		} 
		else {
			MatrixFormatMetaData md = (MatrixFormatMetaData)_metaData;
			if ( md != null ) {
				MatrixCharacteristics mc = ((MatrixDimensionsMetaData)_metaData).getMatrixCharacteristics();
				str.append(mc.toString());
				
				InputInfo ii = md.getInputInfo();
				if ( ii == null )
					str.append("null");
				else {
					if ( InputInfo.inputInfoToString(ii) == null ) {
						try {
							throw new DMLRuntimeException("Unexpected input format");
						} catch (DMLRuntimeException e) {
							e.printStackTrace();
						}
					}
					str.append(InputInfo.inputInfoToString(ii));
				}
			}
			else {
				str.append("null, null");
			}
		}
		str.append(",");
		str.append(isDirty() ? "dirty" : "not-dirty");
		
		return str.toString();
	}
	
	
	// *********************************************
	// ***                                       ***
	// ***    HIGH-LEVEL METHODS THAT SPECIFY    ***
	// ***   THE LOCKING AND CACHING INTERFACE   ***
	// ***                                       ***
	// *********************************************
	
	
	/**
	 * Acquires a shared "read-only" lock, produces the reference to the matrix data,
	 * restores the matrix to main memory, reads from HDFS if needed.
	 * 
	 * Synchronized because there might be parallel threads (parfor local) that
	 * access the same MatrixObjectNew object (in case it was created before the loop).
	 * 
	 * In-Status:  EMPTY, EVICTABLE, EVICTED, READ;
	 * Out-Status: READ(+1).
	 * 
	 * @return the matrix data reference
	 * @throws CacheException 
	 */
	public synchronized MatrixBlock acquireRead()
		throws CacheException
	{
		if( LDEBUG )
			System.out.println("    acquire read "+_varName);
		
		if ( !isAvailableToRead() )
			throw new CacheStatusException ("MatrixObject not available to read.");
		
		//get object from cache
		if( _data == null )
			getCache();
		
		//read data from HDFS if required
		if( isEmpty() ) 
		{
			//check filename
			if( _hdfsFileName == null )
				throw new CacheException("Cannot read matrix for empty filename.");
			
			try
			{
				_data = readMatrixFromHDFS( _hdfsFileName );
				_dirtyFlag = false;
			}
			catch (IOException e)
			{
				throw new CacheIOException("Reading of " + _hdfsFileName + " ("+_varName+") failed.", e);
			}
			
			//mark for initial local write despite read operation
			_requiresLocalWrite = true;
		}
		acquire( false, _data==null );

		return _data;
	}
	
	/**
	 * Acquires the exclusive "write" lock for a thread that wants to change matrix
	 * cell values.  Produces the reference to the matrix data, restores the matrix
	 * to main memory, reads from HDFS if needed.
	 * 
	 * In-Status:  EMPTY, EVICTABLE, EVICTED;
	 * Out-Status: MODIFY.
	 * 
	 * @return the matrix data reference
	 * @throws CacheException 
	 */
	public synchronized MatrixBlock acquireModify() 
		throws CacheException
	{
		if( LDEBUG )
			System.out.println("acquire modify "+_varName);

		if ( !isAvailableToModify() )
			throw new CacheStatusException("MatrixObject not available to modify.");
		
		//get object from cache
		if( _data == null )
			getCache();
		
		//read data from HDFS if required
		if( isEmpty() )
		{
			//check filename
			if( _hdfsFileName == null )
				throw new CacheException("Cannot read matrix for empty filename.");
			
			//load data
			try
			{
				_data = readMatrixFromHDFS( _hdfsFileName );
			}
			catch (IOException e)
			{
				throw new CacheIOException("Reading of " + _hdfsFileName + " ("+_varName+") failed.", e);
			}
		}

		//cache status maintenance
		acquire( true, _data==null );
		_dirtyFlag = true;
		
		return _data;
	}
	
	/**
	 * Acquires the exclusive "write" lock for a thread that wants to throw away the
	 * old matrix data and link up with new matrix data.  Abandons the old matrix data
	 * without reading it.  Sets the new matrix data reference.

	 * In-Status:  EMPTY, EVICTABLE, EVICTED;
	 * Out-Status: MODIFY.
	 * 
	 * @param newData : the new matrix data reference
	 * @return the matrix data reference, which is the same as the argument
	 * @throws CacheException 
	 */
	public synchronized MatrixBlock acquireModify(MatrixBlock newData)
		throws CacheException
	{
		if( LDEBUG )
			System.out.println("    acquire modify newdata "+_varName);
		
		if (! isAvailableToModify ())
			throw new CacheStatusException ("MatrixObject not available to modify.");
		
		//clear old data 
		clearData(); 
		
		//cache status maintenance
		acquire (true, false); //no need to load evicted matrix
		_dirtyFlag = true;
		
		//set references to new data
		if (newData == null)
			throw new CacheException("acquireModify with empty matrix block.");
		_data = newData; 
		
		return _data;
	}

	/**
	 * Releases the shared ("read-only") or exclusive ("write") lock.  Updates
	 * the matrix size, last-access time, metadata, etc.
	 * 
	 * Synchronized because there might be parallel threads (parfor local) that
	 * access the same MatrixObjectNew object (in case it was created before the loop).
	 * 
	 * In-Status:  READ, MODIFY;
	 * Out-Status: READ(-1), EVICTABLE, EMPTY.
	 * 
	 * @throws CacheStatusException
	 */
	@Override
	public synchronized void release() 
		throws CacheException
	{
		if( LDEBUG )
			System.out.println("    release "+_varName);
		
		boolean write = false;
		if ( isModify() )
		{
			write = true;
			_dirtyFlag = true;
			refreshMetaData();
		}
		
		super.release();
		
		if(     isCached() //not empty and not read/modify
		    && (((long)_data.getNumRows())*((long)_data.getNumColumns()) > CACHING_THRESHOLD) ) //min size for caching
		{
			if( write || _requiresLocalWrite ) 
			{
				//evict blob
				String filePath = getCacheFilePathAndName();
				try
				{
					writeMatrix (filePath);
				}
				catch (Exception e)
				{
					throw new CacheException("Eviction to local path " + filePath + " ("+_varName+") failed.", e);
				}
				_requiresLocalWrite = false;
			}
			
			//create cache
			createCache();
			_data = null;			
		}
		else if( LDEBUG )
			System.out.println("Var "+_varName+" not subject to caching: rows="+_data.getNumRows()+", cols="+_data.getNumColumns()+", state="+getStatusAsString()); 
	}

	/**
	 * Sets the matrix data reference to <code>null</code>, abandons the old matrix.
	 * Makes the "envelope" empty.  Run it to finalize the matrix (otherwise the
	 * evicted matrix file may remain undeleted).
	 * 
	 * In-Status:  EMPTY, EVICTABLE, EVICTED;
	 * Out-Status: EMPTY.
	 * @throws CacheException 
	 */
	public synchronized void clearData() 
		throws CacheException
	{
		if( LDEBUG )
			System.out.println("    clear data "+_varName);
		
		if( !_cleanupFlag ) //if cleanup not enabled, do nothing
			return;
		
		if (! isAvailableToModify ())
			throw new CacheStatusException ("MatrixObject (" + this.getDebugName() + ") not available to modify. Status = " + this.getStatusAsString() + ".");
		
		// clear the in-memory data
		_data = null;	
		clearCache();
		
		if (! isEmpty())
		{
			_data = null;
			_dirtyFlag = false;
			setEmpty();
		}
		
		freeEvictedBlob();
	}

	/**
	 * Writes, or flushes, the matrix data to HDFS.
	 * 
	 * In-Status:  EMPTY, EVICTABLE, EVICTED, READ;
	 * Out-Status: EMPTY, EVICTABLE, EVICTED, READ.
	 * 
	 * @throws CacheException 
	 */
	public void exportData()
		throws CacheException
	{
		exportData (_hdfsFileName, null);
		_hdfsFileExists = true;
	}
	
	/**
	 * Synchronized because there might be parallel threads (parfor local) that
	 * access the same MatrixObjectNew object (in case it was created before the loop).
	 * If all threads export the same data object concurrently it results in errors
	 * because they all write to the same file. Efficiency for loops and parallel threads
	 * is achieved by checking if the in-memory matrix block is dirty.
	 * 
	 * NOTE: MB: we do not use dfs copy from local (evicted) to HDFS because this would ignore
	 * the output format and most importantly would bypass reblocking during write (which effects the
	 * potential degree of parallelism). However, we copy files on HDFS if certain criteria are given.  
	 * 
	 * @param fName
	 * @param outputFormat
	 * @throws CacheException
	 */
	public synchronized void exportData (String fName, String outputFormat)
		throws CacheException
	{
		if( LDEBUG ) 
			System.out.println("export data "+_varName+" "+fName);
			
		//prevent concurrent modifications
		if ( !isAvailableToRead() )
			throw new CacheStatusException ("MatrixObject not available to read.");

		if (DMLScript.DEBUG)
			System.out.println("Exporting " + this.getDebugName() + " to " + fName + " in format " + outputFormat);
				
		boolean pWrite = false; // !fName.equals(_hdfsFileName); //persistent write flag
		if ( fName.equals(_hdfsFileName) ) {
			_hdfsFileExists = true;
			pWrite = false;
		}
		else {
			pWrite = true;  // i.e., export is called from "write" instruction
		}

		//actual export (note: no direct transfer of local copy in order to ensure blocking (and hence, parallelism))
		if(  isDirty()  ||      //use dirty for skipping parallel exports
		    (pWrite && !isEqualOutputFormat(outputFormat)) ) 
		{
			// CASE 1: dirty in-mem matrix or pWrite w/ different format (write matrix to fname; load into memory if evicted)
			// a) get the matrix		
			if( isEmpty() )
			{
				//read data from HDFS if required (never read before), this applies only to pWrite w/ different output formats
				try
				{
					_data = readMatrixFromHDFS( _hdfsFileName );
					_dirtyFlag = false;
				}
				catch (IOException e)
				{
					throw new CacheIOException("Reading of " + _hdfsFileName + " ("+_varName+") failed.", e);
				}
			}
			//get object from cache
			if( _data == null )
				getCache();
			acquire( false, _data==null ); //incl. read matrix if evicted	
			
			// b) write the matrix 
			try
			{
				writeMetaData( fName, outputFormat );
				writeMatrixToHDFS( fName, outputFormat );
				if ( !pWrite )
					_dirtyFlag = false;
			}
			catch (Exception e)
			{
				throw new CacheIOException ("Export to " + fName + " failed.", e);
			}
			finally
			{
				release();
			}
		}
		else if( pWrite ) // pwrite with same output format
		{
			//CASE 2: matrix already in same format but different file on hdfs (copy matrix to fname)
			try
			{
				MapReduceTool.deleteFileIfExistOnHDFS(fName);
				MapReduceTool.deleteFileIfExistOnHDFS(fName+".mtd");
				writeMetaData( fName, outputFormat );
				MapReduceTool.copyFileOnHDFS( _hdfsFileName, fName );
			}
			catch (Exception e)
			{
				throw new CacheIOException ("Export to " + fName + " failed.", e);
			}
		}
		else if(DMLScript.DEBUG)  
		{
			//CASE 3: data already in hdfs (do nothing, no need for export)
			System.out.println(this.getDebugName() + ": Skip export to hdfs since data already exists.");
		}
	}

	
	// *********************************************
	// ***                                       ***
	// ***       HIGH-LEVEL PUBLIC METHODS       ***
	// ***     FOR PARTITIONED MATRIX ACCESS     ***
	// ***   (all other methods still usable)    ***
	// ***                                       ***
	// *********************************************
	
	/**
	 * 
	 */
	public void setPartitioned( PDataPartitionFormat format )
	{
		_partitioned = true;
		_partitionFormat = format;
	}
	
	/**
	 * 
	 * @return
	 */
	public boolean isPartitioned()
	{
		return _partitioned;
	}
	
	public PDataPartitionFormat getPartitionFormat()
	{
		return _partitionFormat;
	}
	
	/**
	 * NOTE: for reading matrix partitions, we could cache (in its real sense) the read block
	 * with soft references (no need for eviction, as partitioning only applied for read-only matrices).
	 * However, since we currently only support row- and column-wise partitioning caching is not applied yet.
	 * This could be changed once we also support column-block-wise and row-block-wise. Furthermore,
	 * as we reject to partition vectors and support only full row or column indexing, no metadata (apart from
	 * the partition flag) is required.  
	 * 
	 * @param pred
	 * @return
	 * @throws CacheException
	 */
	public synchronized MatrixBlock readMatrixPartition( IndexRange pred ) 
		throws CacheException
	{
		if ( !_partitioned )
			throw new CacheStatusException ("MatrixObject not available to indexed read.");
		
		MatrixBlock mb = null;
		
		try
		{
			boolean blockwise = (_partitionFormat==PDataPartitionFormat.ROW_BLOCK_WISE || _partitionFormat==PDataPartitionFormat.COLUMN_BLOCK_WISE);
			
			//preparations for block wise access
			MatrixFormatMetaData iimd = (MatrixFormatMetaData) _metaData;
			MatrixCharacteristics mc = iimd.getMatrixCharacteristics();
			int brlen = mc.get_rows_per_block();
			int bclen = mc.get_cols_per_block();
			
			//get filename depending on format
			String fname = getPartitionFileName( pred, brlen, bclen );
			
			//probe cache
			if( blockwise && _partitionCacheName != null && _partitionCacheName.equals(fname) )
			{
				mb = _cache.get(); //try getting block from cache
			}
			
			if( mb == null ) //block not in cache
			{
				//get rows and cols
				long rows = -1;
				long cols = -1;
				switch( _partitionFormat )
				{
					case ROW_WISE:
						rows = 1;
						cols = mc.get_cols();
						break;
					case ROW_BLOCK_WISE: 
						rows = brlen;
						cols = mc.get_cols();
						break;
					case COLUMN_WISE:
						rows = mc.get_rows();
						cols = 1;
						break;
					case COLUMN_BLOCK_WISE: 
						rows = mc.get_rows();
						cols = bclen;
						break;
				}
				
				
				//read the 
				if( MapReduceTool.existsFileOnHDFS(fname) )
					mb = readMatrixFromHDFS( fname, rows, cols );
				else
				{
					mb = new MatrixBlock((int)rows, (int)cols, true);
					System.out.println("Warning: Reading empty matrix partition "+fname);
				}
			}
			
			//post processing
			if( blockwise )
			{
				//put block into cache
				_partitionCacheName = fname;
				_cache = new SoftReference<MatrixBlock>(mb);
				
				if( _partitionFormat == PDataPartitionFormat.ROW_BLOCK_WISE )
					mb = projectRow(mb, brlen, (int)pred.rowStart);
				if( _partitionFormat == PDataPartitionFormat.COLUMN_BLOCK_WISE )
					mb = projectColumn(mb, bclen, (int)pred.colStart);
			}
			
			
			//NOTE: currently no special treatment of non-existing partitions necessary 
			//      because empty blocks are written anyway
		}
		catch(Exception ex)
		{
			throw new CacheException(ex);
		}
		
		return mb;
	}
	
	
	/**
	 * 
	 * @param pred
	 * @return
	 * @throws CacheStatusException 
	 */
	private String getPartitionFileName( IndexRange pred, int brlen, int bclen ) 
		throws CacheStatusException
	{
		if ( !_partitioned )
			throw new CacheStatusException ("MatrixObject not available to indexed read.");
		
		StringBuilder sb = new StringBuilder();
		sb.append(_hdfsFileName);
		
		switch( _partitionFormat )
		{
			case ROW_WISE:
				sb.append(Lops.FILE_SEPARATOR);
				sb.append(pred.rowStart); 
				break;
			case ROW_BLOCK_WISE:
				sb.append(Lops.FILE_SEPARATOR);
				sb.append(pred.rowStart/brlen+1);
				break;
			case COLUMN_WISE:
				sb.append(Lops.FILE_SEPARATOR);
				sb.append(pred.colStart);
				break;
			case COLUMN_BLOCK_WISE:
				sb.append(Lops.FILE_SEPARATOR);
				sb.append(pred.colStart/bclen+1);
				break;
			default:
				throw new CacheStatusException ("MatrixObject not available to indexed read.");
		}

		return sb.toString();
	}	
	
	/**
	 * 
	 * @param mb
	 * @param brlen
	 * @param rindex
	 * @return
	 */
	private MatrixBlock projectRow( MatrixBlock mb, int brlen, int rindex )
	{
		int brindex = rindex%brlen-1;
		int clen = mb.getNumColumns();
		
		MatrixBlock tmp = new MatrixBlock( 1, clen, false );
		tmp.spaceAllocForDenseUnsafe(1, clen);
		for( int j=0; j<clen; j++ ) //see DataPartitioniner, mb always dense
			tmp.setValueDenseUnsafe(0, j, mb.getValueDenseUnsafe(brindex, j));
		tmp.recomputeNonZeros();
		
		return tmp;
	}
	
	/**
	 * 
	 * @param mb
	 * @param bclen
	 * @param cindex
	 * @return
	 */
	private MatrixBlock projectColumn( MatrixBlock mb, int bclen, int cindex )
	{
		int bcindex = cindex%bclen-1;
		int rlen = mb.getNumRows();
		
		MatrixBlock tmp = new MatrixBlock( rlen, 1, false );
		tmp.spaceAllocForDenseUnsafe(rlen, 1);
		for( int i=0; i<rlen; i++ ) //see DataPartitioniner, mb always dense
			tmp.setValueDenseUnsafe(i, 0, mb.getValueDenseUnsafe(i, bcindex));
		tmp.recomputeNonZeros();
		
		return tmp;
	}
	

	// *********************************************
	// ***                                       ***
	// ***      LOW-LEVEL PROTECTED METHODS      ***
	// ***         EXTEND CACHEABLE DATA         ***
	// ***     ONLY CALLED BY THE SUPERCLASS     ***
	// ***                                       ***
	// *********************************************
	

	@Override
	public boolean isBlobPresent()
	{
		return (_data != null);
	}

	@Override
	protected void evictBlobFromMemory ( MatrixBlock mb ) 
		throws CacheIOException
	{
		throw new CacheIOException("Redundant explicit eviction.");
	}
	
	@Override
	protected void restoreBlobIntoMemory () 
		throws CacheIOException, CacheAssignmentException
	{
		if( LDEBUG ) 
			System.out.println("RESTORE of Matrix "+_varName+", "+_hdfsFileName);
		
		String filePath = getCacheFilePathAndName();
		long begin = 0;
		if (DMLScript.DEBUG) 
		{
			System.out.println ("\t CACHE: Restoring matrix...  " + _varName + "  HDFS path: " + 
					(_hdfsFileName == null ? "null" : _hdfsFileName));
			System.out.println ("\t        Restore from path: " + filePath);
			begin = System.currentTimeMillis();
		}
		
		if (_data != null)
			throw new CacheIOException (filePath + " : Cannot restore on top of existing in-memory data.");

		try
		{
			_data = readMatrix(filePath);
		}
		catch (IOException e)
		{
			throw new CacheIOException (filePath + " : Restore failed.", e);	
		}
		
		//check for success
	    if (_data == null)
			throw new CacheIOException (filePath + " : Restore failed.");
	    
		if (DMLScript.DEBUG) 
		{
			System.out.println ("\t\tRestoring matrix - COMPLETED ... " + (System.currentTimeMillis()-begin) + " msec.");
		}
	}		

	@Override
	protected void freeEvictedBlob()
	{
		String cacheFilePathAndName = getCacheFilePathAndName();
		long begin = 0;
		if (DMLScript.DEBUG) 
		{
			System.out.println ("\t CACHE: Freeing evicted matrix...  " + _varName + "  HDFS path: " + 
					(_hdfsFileName == null ? "null" : _hdfsFileName));
			System.out.println ("\t        Eviction path: " + cacheFilePathAndName);
			begin = System.currentTimeMillis();
		}
		
		switch (CacheableData.cacheEvictionStorageType)
		{
			case LOCAL:
				File f = new File(cacheFilePathAndName);
				if( f.exists() )
					f.delete();
				break;
			case HDFS:
				try{
					MapReduceTool.deleteFileIfExistOnHDFS(cacheFilePathAndName); 
				}
				catch (IOException e){}
				break;
		}
		
		if (DMLScript.DEBUG) 
		{
			System.out.println ("\t\tFreeing evicted matrix - COMPLETED ... " + (System.currentTimeMillis()-begin) + " msec.");
		}
	}
	
	// *******************************************
	// ***                                     ***
	// ***      LOW-LEVEL PRIVATE METHODS      ***
	// ***           FOR MATRIX I/O            ***
	// ***                                     ***
	// *******************************************
	
	/**
	 * 
	 */
	private String getCacheFilePathAndName ()
	{
		if( _cacheFileName==null )
		{
			StringBuilder sb = new StringBuilder();
			switch (CacheableData.cacheEvictionStorageType)
			{
				case LOCAL:
					sb.append(CacheableData.cacheEvictionLocalFilePath); 
					sb.append(CacheableData.cacheEvictionLocalFilePrefix);
					sb.append(String.format ("%09d", getUniqueCacheID()));
					sb.append(CacheableData.cacheEvictionLocalFileExtension);
					break;
					
				case HDFS:
					sb.append(CacheableData.cacheEvictionHDFSFilePath);
					sb.append(CacheableData.cacheEvictionHDFSFilePrefix);
					sb.append(String.format ("%09d", getUniqueCacheID()));
					sb.append(CacheableData.cacheEvictionHDFSFileExtension);
					break;
			}
			
			_cacheFileName = sb.toString();
		}
		
		return _cacheFileName;
	}
	
	/**
	 * 
	 * @param filePathAndName
	 * @return
	 * @throws IOException
	 */
	private MatrixBlock readMatrix (String filePathAndName)
		throws IOException
	{
		//System.out.println("read matrix "+filePathAndName);
		
		MatrixBlock newData = null;
		switch (CacheableData.cacheEvictionStorageType)
		{
			case LOCAL:
				newData = LocalFileUtils.readMatrixBlockFromLocal(filePathAndName);
				break;			
			case HDFS:
				newData = readMatrixFromHDFS (filePathAndName);
				break;
			default:
				throw new IOException (filePathAndName + 
						" : Cannot read matrix from unsupported storage type \""
						+ CacheableData.cacheEvictionStorageType + "\"");				
		}
		return newData;
	}
	
	/**
	 * 
	 * @param filePathAndName
	 * @return
	 * @throws IOException
	 */
	private MatrixBlock readMatrixFromHDFS(String filePathAndName)
		throws IOException
	{
		MatrixFormatMetaData iimd = (MatrixFormatMetaData) _metaData;
		MatrixCharacteristics mc = iimd.getMatrixCharacteristics();
		return readMatrixFromHDFS( filePathAndName, mc.get_rows(), mc.get_cols() );
	}
	
	/**
	 * 
	 * @param filePathAndName
	 * @param rlen
	 * @param clen
	 * @return
	 * @throws IOException
	 */
	private MatrixBlock readMatrixFromHDFS(String filePathAndName, long rlen, long clen)
		throws IOException
	{

		long begin = 0;
		if (DMLScript.DEBUG) 
		{
			System.out.println ("    Reading matrix from HDFS...  " + _varName + "  Path: " + filePathAndName);
			begin = System.currentTimeMillis();
		}
	
		MatrixFormatMetaData iimd = (MatrixFormatMetaData) _metaData;
		MatrixCharacteristics mc = iimd.getMatrixCharacteristics();
		double sparsity = ((double)mc.nonZero)/(mc.numRows*mc.numColumns); //expected sparsity
		MatrixBlock newData = DataConverter.readMatrixFromHDFS(filePathAndName, iimd.getInputInfo(),
				                           rlen, clen, mc.numRowsPerBlock, mc.numColumnsPerBlock, sparsity);
		
		if( newData == null )
		{
			throw new IOException("Unable to load matrix from file "+filePathAndName);
		}
		
		if (DMLScript.DEBUG) 
		{
			System.out.println ("    Reading Completed: " + (System.currentTimeMillis()-begin) + " msec.");
		}
		return newData;
	}
	
	/**
	 * 
	 * @param filePathAndName
	 * @throws DMLRuntimeException
	 * @throws IOException
	 */
	private void writeMatrix (String filePathAndName)
		throws DMLRuntimeException, IOException
	{
		//System.out.println("write matrix "+filePathAndName );
		
		switch (CacheableData.cacheEvictionStorageType)
		{
			case LOCAL:
				LocalFileUtils.writeMatrixBlockToLocal(filePathAndName, _data);
				break;			
			case HDFS:
				writeMatrixToHDFS (filePathAndName, null);
				break;
			default:
				throw new IOException (filePathAndName + 
						" : Cannot write matrix to unsupported storage type \""
						+ CacheableData.cacheEvictionStorageType + "\"");				
		}
	}

	/**
	 * Writes in-memory matrix to HDFS in a specified format.
	 * 
	 * @throws DMLRuntimeException
	 * @throws IOException
	 */
	private void writeMatrixToHDFS (String filePathAndName, String outputFormat)
		throws DMLRuntimeException, IOException
	{
		//System.out.println("write matrix "+_varName+" "+filePathAndName);
		
		long begin = 0;
		if (DMLScript.DEBUG) 
		{
			System.out.println ("    Writing matrix to HDFS...  " + _varName + "  Path: " + filePathAndName + ", Format: " +
					(outputFormat != null ? outputFormat : "inferred from metadata"));
			begin = System.currentTimeMillis();
		}
		
		MatrixFormatMetaData iimd = (MatrixFormatMetaData) _metaData;

		if (_data != null)
		{
			// Get the dimension information from the metadata stored within MatrixObject
			MatrixCharacteristics mc = iimd.getMatrixCharacteristics ();
			// Write the matrix to HDFS in requested format
			OutputInfo oinfo = (outputFormat != null ? OutputInfo.stringToOutputInfo (outputFormat) 
					                                 : InputInfo.getMatchingOutputInfo (iimd.getInputInfo ()));
			
			// when outputFormat is binaryblock, make sure that matrixCharacteristics has correct blocking dimensions
			if ( oinfo == OutputInfo.BinaryBlockOutputInfo && 
					(mc.get_rows_per_block() != DMLTranslator.DMLBlockSize || mc.get_cols_per_block() != DMLTranslator.DMLBlockSize) ) {
				DataConverter.writeMatrixToHDFS(_data, filePathAndName, oinfo, mc.get_rows(), mc.get_cols(), DMLTranslator.DMLBlockSize, DMLTranslator.DMLBlockSize);
			}
			else {
				DataConverter.writeMatrixToHDFS(_data, filePathAndName, oinfo, mc.get_rows(), mc.get_cols(), mc.get_rows_per_block(), mc.get_cols_per_block());
			}

			if (DMLScript.DEBUG) 
			{
				System.out.println ("    Writing matrix to HDFS ("+filePathAndName+") - COMPLETED... " + (System.currentTimeMillis()-begin) + " msec.");
			}
		}
		else if (DMLScript.DEBUG) 
		{
			System.out.println ("Writing matrix to HDFS ("+filePathAndName+") - NOTHING TO WRITE (_data == null).");
		}
	}
	
	/**
	 * 
	 * @param filePathAndName
	 * @param outputFormat
	 * @throws DMLRuntimeException
	 * @throws IOException
	 */
	private void writeMetaData (String filePathAndName, String outputFormat)
		throws DMLRuntimeException, IOException
	{
		MatrixFormatMetaData iimd = (MatrixFormatMetaData) _metaData;
	
		if (iimd != null)
		{
			// Get the dimension information from the metadata stored within MatrixObject
			MatrixCharacteristics mc = iimd.getMatrixCharacteristics ();
			
			// Write the matrix to HDFS in requested format			
			OutputInfo oinfo = (outputFormat != null ? OutputInfo.stringToOutputInfo (outputFormat) 
                    : InputInfo.getMatchingOutputInfo (iimd.getInputInfo ()));
			
			// when outputFormat is binaryblock, make sure that matrixCharacteristics has correct blocking dimensions
			if ( oinfo == OutputInfo.BinaryBlockOutputInfo && 
					(mc.get_rows_per_block() != DMLTranslator.DMLBlockSize || mc.get_cols_per_block() != DMLTranslator.DMLBlockSize) ) {
				mc = new MatrixCharacteristics(mc.get_rows(), mc.get_cols(), DMLTranslator.DMLBlockSize, DMLTranslator.DMLBlockSize, mc.getNonZeros());
			}
			MapReduceTool.writeMetaDataFile (filePathAndName + ".mtd", valueType, mc, oinfo);
		}
		else {
			throw new DMLRuntimeException("Unexpected error while writing mtd file (" + filePathAndName + ") -- metadata is null.");
		}
	}
	
	/**
	 * 
	 * @param outputFormat
	 * @return
	 */
	private boolean isEqualOutputFormat( String outputFormat )
	{
		boolean ret = true;
		
		if( outputFormat != null )
		{
			try
			{
				MatrixFormatMetaData iimd = (MatrixFormatMetaData) _metaData;
				OutputInfo oi1 = InputInfo.getMatchingOutputInfo( iimd.getInputInfo() );
				OutputInfo oi2 = OutputInfo.stringToOutputInfo( outputFormat );
				if( oi1 != oi2 )
				{
					ret = false;
				}
			}
			catch(Exception ex)
			{
				ret = false;
			}
		}
		
		return ret;
	}
	
	@Override
	public synchronized String getDebugName()
	{
		int maxLength = 23;
		String debugNameEnding = (_hdfsFileName == null ? "null" : 
			(_hdfsFileName.length() < maxLength ? _hdfsFileName : "..." + 
				_hdfsFileName.substring (_hdfsFileName.length() - maxLength + 3)));
		return _varName + " " + debugNameEnding;
	}

	
	// *******************************************
	// ***                                     ***
	// ***      LOW-LEVEL PRIVATE METHODS      ***
	// ***       FOR SOFTREFERENCE CACHE       ***
	// ***                                     ***
	// *******************************************
	
	/**
	 * 
	 */
	private void createCache( ) 
	{
		_cache = new SoftReference<MatrixBlock>( _data );	
	}

	/**
	 * 
	 */
	private void getCache()
	{
		if( _cache !=null )
		{
			_data = _cache.get();
			clearCache();
		}
	}

	/**
	 * 
	 */
	private void clearCache()
	{
		if( _cache != null )
		{
			_cache.clear();
			_cache = null;
		}
	}
	
	/**
	 * see clear data
	 * 
	 * @param flag
	 */
	public void enableCleanup(boolean flag) 
	{
		_cleanupFlag = flag;
	}

	/**
	 * see clear data
	 * 
	 * @return
	 */
	public boolean isCleanupEnabled() 
	{
		return _cleanupFlag;
	}

}