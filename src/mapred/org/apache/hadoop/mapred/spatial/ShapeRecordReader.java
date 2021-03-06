package org.apache.hadoop.mapred.spatial;

import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.lib.CombineFileSplit;
import org.apache.hadoop.spatial.CellInfo;
import org.apache.hadoop.spatial.Point;
import org.apache.hadoop.spatial.Shape;
import org.apache.hadoop.spatial.SpatialSite;

/**
 * A record reader for objects of class {@link Shape}
 * @author eldawy
 *
 */
public class ShapeRecordReader<S extends Shape>
    extends SpatialRecordReader<CellInfo, S> {
  
  @SuppressWarnings("unused")
  private static final Log LOG = LogFactory.getLog(ShapeRecordReader.class);

  /**Object used for deserialization*/
  private S stockShape;

  public ShapeRecordReader(Configuration job, FileSplit split)
      throws IOException {
    super(job, split);
    stockShape = createStockShape(job);
  }

  public ShapeRecordReader(CombineFileSplit split, Configuration conf,
      Reporter reporter, Integer index) throws IOException {
    super(split, conf, reporter, index);
    stockShape = createStockShape(conf);
  }
  
  public ShapeRecordReader(InputStream in, long offset, long endOffset)
      throws IOException {
    super(in, offset, endOffset);
  }

  @Override
  public boolean next(CellInfo key, S shape) throws IOException {
    boolean read_line = nextShape(shape, true);
    key.set(cellInfo);
    return read_line;
  }

  @Override
  public CellInfo createKey() {
    return new CellInfo();
  }

  @Override
  public S createValue() {
    return stockShape;
  }

  @SuppressWarnings("unchecked")
  private S createStockShape(Configuration job) {
    S stockShape = null;
    String shapeClassName =
        job.get(SpatialSite.SHAPE_CLASS, Point.class.getName());
    try {
      Class<? extends Shape> shapeClass =
          Class.forName(shapeClassName).asSubclass(Shape.class);
      stockShape = (S) shapeClass.newInstance();
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
    } catch (InstantiationException e) {
      e.printStackTrace();
    } catch (IllegalAccessException e) {
      e.printStackTrace();
    }
    return stockShape;
  }
}
