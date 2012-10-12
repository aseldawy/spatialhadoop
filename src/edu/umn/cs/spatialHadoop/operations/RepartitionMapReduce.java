package edu.umn.cs.spatialHadoop.operations;

import java.io.IOException;
import java.util.Iterator;
import java.util.Vector;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.spatial.CellInfo;
import org.apache.hadoop.spatial.GridInfo;
import org.apache.hadoop.spatial.RTree;
import org.apache.hadoop.spatial.Shape;
import org.apache.hadoop.spatial.TigerShape;
import org.apache.hadoop.spatial.WriteGridFile;

import edu.umn.cs.CommandLineArguments;
import edu.umn.cs.Estimator;
import edu.umn.cs.spatialHadoop.mapReduce.GridOutputFormat;

/**
 * Repartitions a file according to a different grid through a MapReduce job
 * @author aseldawy
 *
 */
public class RepartitionMapReduce {
  static final Log LOG = LogFactory.getLog(RepartitionMapReduce.class);
  
  /**Configuration line name for replication overhead*/
  public static final String REPLICATION_OVERHEAD =
      "spatialHadoop.storage.ReplicationOverHead";
  
  /**
   * The map class maps each object to all cells it overlaps with.
   * @author eldawy
   *
   */
  public static class Map extends MapReduceBase
  implements
  Mapper<LongWritable, TigerShape, IntWritable, TigerShape> {
    /**List of cells used by the mapper*/
    private CellInfo[] cellInfos;
    
    @Override
    public void configure(JobConf job) {
      String cellsInfoStr = job.get(GridOutputFormat.OUTPUT_CELLS);
      cellInfos = GridOutputFormat.decodeCells(cellsInfoStr);
      super.configure(job);
    }

    static IntWritable cellId = new IntWritable();
    public void map(
        LongWritable id,
        TigerShape shape,
        OutputCollector<IntWritable, TigerShape> output,
        Reporter reporter) throws IOException {

      for (int cellIndex = 0; cellIndex < cellInfos.length; cellIndex++) {
        if (cellInfos[cellIndex].isIntersected(shape)) {
          cellId.set((int)cellInfos[cellIndex].cellId);
          output.collect(cellId, shape);
        }
      }
    }
  }
  
  /**
   * The reducer writes records to the cell they belong to. It also  finializes
   * the cell by writing a <code>null</code> object after all objects.
   * @author eldawy
   *
   */
  public static class Reduce extends MapReduceBase implements
  Reducer<IntWritable, TigerShape, CellInfo, TigerShape> {
    /**List of cells used by the reducer*/
    private CellInfo[] cellInfos;

    @Override
    public void configure(JobConf job) {
      String cellsInfoStr = job.get(GridOutputFormat.OUTPUT_CELLS);
      cellInfos = GridOutputFormat.decodeCells(cellsInfoStr);
      super.configure(job);
    }
    
    @Override
    public void reduce(IntWritable cellId, Iterator<TigerShape> values,
        OutputCollector<CellInfo, TigerShape> output, Reporter reporter)
            throws IOException {
      CellInfo cellInfo = null;
      for (CellInfo _cellInfo : cellInfos) {
        if (_cellInfo.cellId == cellId.get())
          cellInfo = _cellInfo;
      }
      // If writing to a grid file, concatenated in text
      while (values.hasNext()) {
        TigerShape value = values.next();
        output.collect(cellInfo, value);
      }
      // Close this cell as we will not write any more data to it
      output.collect(cellInfo, null);
    }
  }
  
  /**
   * Repartitions a file that is already in HDFS. It runs a MapReduce job
   * that partitions the file into cells, and writes each cell separately.
   * @param conf
   * @param inFile
   * @param outPath
   * @param gridInfo
   * @param pack
   * @param rtree
   * @param overwrite
   * @throws IOException
   */
  public static void repartition(JobConf conf, Path inFile, Path outPath,
      GridInfo gridInfo, boolean pack, boolean rtree, boolean overwrite)
          throws IOException {
    conf.setJobName("Repartition");
    
    FileSystem inFs = inFile.getFileSystem(conf);
    FileSystem outFs = outPath.getFileSystem(conf);

    if (gridInfo == null)
      gridInfo = WriteGridFile.getGridInfo(inFs, inFile, outFs);
    if (gridInfo.columns == 0 || rtree) {
      // Recalculate grid dimensions
      int num_cells = calculateNumberOfPartitions(inFs, inFile, outFs, rtree);
      gridInfo.calculateCellDimensions(num_cells);
    }
    CellInfo[] cellsInfo = pack ?
        WriteGridFile.packInRectangles(inFs, inFile, outFs, gridInfo) :
          gridInfo.getAllCells();

    // Overwrite output file
    if (inFs.exists(outPath)) {
      if (overwrite)
        outFs.delete(outPath, true);
      else
        throw new RuntimeException("Output file '" + outPath
            + "' already exists and overwrite flag is not set");
    }

    conf.setInputFormat(ShapeInputFormat.class);
    ShapeInputFormat.setInputPaths(conf, inFile);
    conf.setMapOutputKeyClass(IntWritable.class);
    conf.setMapOutputValueClass(TigerShape.class);

    conf.setMapperClass(Map.class);
    conf.setReducerClass(Reduce.class);

    // Set default parameters for reading input file
    conf.set(ShapeRecordReader.SHAPE_CLASS, TigerShape.class.getName());

    FileOutputFormat.setOutputPath(conf,outPath);
    conf.setOutputFormat(rtree ? RTreeGridOutputFormat.class : GridOutputFormat.class);
    conf.set(GridOutputFormat.OUTPUT_CELLS,
        GridOutputFormat.encodeCells(cellsInfo));
    conf.setBoolean(GridOutputFormat.OVERWRITE, overwrite);

    JobClient.runJob(conf);
    
    // Combine all output files into one file as we do with grid files
    Vector<Path> pathsToConcat = new Vector<Path>();
    FileStatus[] resultFiles = inFs.listStatus(outPath);
    for (int i = 0; i < resultFiles.length; i++) {
      FileStatus resultFile = resultFiles[i];
      if (resultFile.getLen() > 0 &&
          resultFile.getLen() % resultFile.getBlockSize() == 0) {
        Path partFile = new Path(outPath.toUri().getPath()+"_"+i);
        outFs.rename(resultFile.getPath(), partFile);
        LOG.info("Rename "+resultFile.getPath()+" -> "+partFile);
        pathsToConcat.add(partFile);
      }
    }
    
    LOG.info("Concatenating: "+pathsToConcat+" into "+outPath);
    if (outFs.exists(outPath))
      outFs.delete(outPath, true);
    if (pathsToConcat.size() == 1) {
      outFs.rename(pathsToConcat.firstElement(), outPath);
    } else if (!pathsToConcat.isEmpty()) {
      Path target = pathsToConcat.lastElement();
      pathsToConcat.remove(pathsToConcat.size()-1);
      outFs.concat(target,
          pathsToConcat.toArray(new Path[pathsToConcat.size()]));
      outFs.rename(target, outPath);
    }
  }
  
  /**
   * Calculates number of partitions required to index the given file
   * @param inFs
   * @param file
   * @param rtree
   * @return
   * @throws IOException 
   */
  static int calculateNumberOfPartitions(FileSystem inFs, Path file,
      FileSystem outFs,
      boolean rtree) throws IOException {
    Configuration conf = inFs.getConf();
    final float ReplicationOverhead = conf.getFloat(REPLICATION_OVERHEAD,
        0.001f);
    final long fileSize = inFs.getFileStatus(file).getLen();
    if (!rtree) {
      long indexedFileSize = (long) (fileSize * (1 + ReplicationOverhead));
      return (int)Math.ceil((float)indexedFileSize / outFs.getDefaultBlockSize());
    } else {
      final int RTreeDegree = conf.getInt(RTreeGridRecordWriter.RTREE_DEGREE, 11);
      Estimator<Integer> estimator = new Estimator<Integer>(0.01);
      final FSDataInputStream in = inFs.open(file);
      Class<? extends Shape> recordClass =
          conf.getClass(ShapeRecordReader.SHAPE_CLASS, TigerShape.class).
          asSubclass(Shape.class);
      int record_size = RTreeGridRecordWriter.calculateRecordSize(recordClass);
      long blockSize = conf.getLong(RTreeGridRecordWriter.RTREE_BLOCK_SIZE,
          outFs.getDefaultBlockSize());
      
      LOG.info("RTree block size: "+blockSize);
      final int records_per_block =
          RTree.getBlockCapacity(blockSize, RTreeDegree, record_size);
      LOG.info("RTrees can hold up to: "+records_per_block+" recods");
      
      estimator.setRandomSample(new Estimator.RandomSample() {
        
        @Override
        public double next() {
          int lineLength = 0;
          try {
            long randomFilePosition = (long)(Math.random() * fileSize);
            in.seek(randomFilePosition);
            
            // Skip the rest of this line
            byte lastReadByte;
            do {
              lastReadByte = in.readByte();
            } while (lastReadByte != '\n' && lastReadByte != '\r');

            while (in.getPos() < fileSize - 1) {
              lastReadByte = in.readByte();
              if (lastReadByte == '\n' || lastReadByte == '\r') {
                break;
              }
              lineLength++;
            }
          } catch (IOException e) {
            e.printStackTrace();
          }
          return lineLength+1;
        }
      });

      estimator.setUserFunction(new Estimator.UserFunction<Integer>() {
        @Override
        public Integer calculate(double x) {
          double lineCount = fileSize / x;
          double indexedRecordCount = lineCount * (1.0 + ReplicationOverhead);
          return (int) Math.ceil(indexedRecordCount / records_per_block);
        }
      });
      
      estimator.setQualityControl(new Estimator.QualityControl<Integer>() {
        @Override
        public boolean isAcceptable(Integer y1, Integer y2) {
          return (double)Math.abs(y2 - y1) / Math.min(y1, y2) < 0.01;
        }
      });
   
      Estimator.Range<Integer> blockCount = estimator.getEstimate();
      in.close();
      LOG.info("block count range ["+ blockCount.limit1 + ","
          + blockCount.limit2 + "]");
      return Math.max(blockCount.limit1, blockCount.limit2);
    }
  }
	
	/**
	 * Entry point to the file.
	 * ... grid:<gridInfo> [-pack] [-rtree] <input filenames> <output filename>
	 * gridInfo in the format <x1,y1,w,h[,cw,ch]>
	 * input filenames: Input file in HDFS
	 * output filename: Output file in HDFS
	 * @param args
	 * @throws Exception
	 */
	public static void main(String[] args) throws Exception {
    JobConf conf = new JobConf(RepartitionMapReduce.class);
    CommandLineArguments cla = new CommandLineArguments(args);
    Path inputPath = cla.getInputPath();
    Path outputPath = cla.getOutputPath();
    
    GridInfo gridInfo = cla.getGridInfo();
    
    boolean rtree = cla.isRtree();
    boolean pack = cla.isPack();
    boolean overwrite = cla.isOverwrite();
    
    repartition(conf, inputPath, outputPath, gridInfo, pack, rtree, overwrite);
	}
}
