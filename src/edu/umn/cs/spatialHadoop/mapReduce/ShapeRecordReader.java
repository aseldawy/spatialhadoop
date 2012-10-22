package edu.umn.cs.spatialHadoop.mapReduce;

import java.io.IOException;
import java.io.InputStream;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.spatial.Point;
import org.apache.hadoop.spatial.Shape;


/**
 * A record reader for objects of class {@link Shape}
 * @author eldawy
 *
 */
public class ShapeRecordReader<S extends Shape> extends STextRecordReader<S> {
  /**Configuration line to set the default shape class to use if not set*/
  public static final String SHAPE_CLASS =
      "edu.umn.cs.spatialHadoop.ShapeRecordReader.ShapeClass.default";

  /**A stock shape to use for deserialization (value)*/
  private S stockShape;
  
  public ShapeRecordReader(Configuration job, FileSplit split)
      throws IOException {
    super(job, split);
    stockShape = createStockShape(job);
  }

  public ShapeRecordReader(InputStream in, long offset, long endOffset) {
    super(in, offset, endOffset);
  }

  @Override
  public S createValue() {
    return stockShape;
  }

  private S createStockShape(Configuration job) {
    S stockShape = null;
    String shapeClassName = job.get(SHAPE_CLASS, Point.class.getName());
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
