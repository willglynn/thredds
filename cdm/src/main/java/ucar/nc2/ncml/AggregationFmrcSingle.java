// $Id: $
/*
 * Copyright 1997-2006 Unidata Program Center/University Corporation for
 * Atmospheric Research, P.O. Box 3000, Boulder, CO 80307,
 * support@unidata.ucar.edu.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or (at
 * your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; if not, write to the Free Software Foundation,
 * Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */

package ucar.nc2.ncml;

import ucar.nc2.dataset.*;
import ucar.nc2.util.CancelTask;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Attribute;
import ucar.nc2.Dimension;
import ucar.nc2.units.DateFormatter;
import ucar.nc2.dt.GridDataset;
import ucar.nc2.dt.GridDatatype;
import ucar.nc2.dt.GridCoordSystem;
import ucar.nc2.dt.fmrc.ForecastModelRunInventory;
import ucar.unidata.util.StringUtil;
import ucar.ma2.Array;
import ucar.ma2.Index;

import java.io.IOException;
import java.util.*;

import thredds.util.DateFromString;

/**
 * Implement NcML Forecast Model Run Collection Aggregation
 *  with files that contain a single forecast time.
 *
 * @author caron
 */
public class AggregationFmrcSingle extends AggregationFmrc {
  private ArrayList scanFmrcList = new ArrayList(); // current set of DirectoryScan for scanFmrc elements
  private Calendar cal = new GregorianCalendar(); // for date computations

  private HashMap runHash = new HashMap(); // Hash<Date, List>
  private ArrayList runs; // List<Date>

  private CoordinateAxis1D timeAxis = null;
  private int max_times = 0;
  private Dataset typicalDataset = null;
  private boolean debug = false;

  public AggregationFmrcSingle(NetcdfDataset ncd, String dimName, String recheckS) {
    super(ncd, dimName, Type.FORECAST_MODEL_SINGLE, recheckS);
  }

  public void addDirectoryScanFmrc(String dirName, String suffix, String regexpPatternString, String subdirs, String olderThan,
          String runMatcher, String forecastMatcher, String offsetMatcher) throws IOException {
    DirectoryScan d = new DirectoryScan(dirName, suffix, regexpPatternString, subdirs, olderThan, runMatcher, forecastMatcher, offsetMatcher);
    scanFmrcList.add(d);
    isDate = true;
  }

  protected void buildDataset(boolean isNew, CancelTask cancelTask) throws IOException {
    GridDataset typicalGds = scanFmrc(cancelTask);
    if (typicalGds == null) return;
    buildDataset( typicalDataset, typicalGds, cancelTask);
  }

  protected GridDataset scanFmrc(CancelTask cancelTask) throws IOException {
    // scan the directories
    ArrayList fileList = new ArrayList();
    for (int i = 0; i < scanFmrcList.size(); i++) {
      DirectoryScan dir = (DirectoryScan) scanFmrcList.get(i);
      dir.scanDirectory( fileList, cancelTask);

      if ((cancelTask != null) && cancelTask.isCancel())
        return null;
    }

    // find the runtime, forecast time coordinates, put in list
    for (int i = 0; i < fileList.size(); i++) {
      MyFile myf = (MyFile) fileList.get(i);
      String location = StringUtil.replace( myf.file.getAbsolutePath(), '\\', "/");

      // parse for rundate
      if (myf.dir.runMatcher != null) {
        myf.runDate = DateFromString.getDateUsingDemarkatedMatch(location, myf.dir.runMatcher, '#');
        if (null == myf.runDate) {
          logger.error("Cant extract rundate from =" + location+" using format "+myf.dir.runMatcher);
          continue;
        }
      }

      // parse for forecast date
      if (myf.dir.forecastMatcher != null) {
        myf.dateCoord = DateFromString.getDateUsingDemarkatedMatch(location, myf.dir.forecastMatcher, '#');
        if (null == myf.dateCoord) {
          logger.error("Cant extract forecast date from =" + location+" using format "+myf.dir.forecastMatcher);
          continue;
        }
        myf.dateCoordS = formatter.toDateTimeStringISO(myf.dateCoord);
      }

      // parse for forecast offset
      if (myf.dir.offsetMatcher != null) {
        myf.offset = DateFromString.getHourUsingDemarkatedMatch(location, myf.dir.offsetMatcher, '#');
        if (null == myf.offset) {
          logger.error("Cant extract forecast offset from =" + location+" using format "+myf.dir.offsetMatcher);
          continue;
        }
        myf.dateCoord = addHour(myf.runDate, myf.offset.doubleValue());
        myf.dateCoordS = formatter.toDateTimeStringISO(myf.dateCoord);
      }

      // create the dataset wrapping this file, each is 1 forecast time coordinate of the nested aggregation
      Dataset ds = makeDataset(location, location, null, myf.dateCoordS, true, null);
      ds.coordValueDate = myf.dateCoord;
      ds.ncoord = 1;

      // add to list for given run date
      List runDatasets = (List) runHash.get( myf.runDate);
      if (runDatasets == null) {
        runDatasets = new ArrayList();
        runHash.put( myf.runDate, runDatasets);
      }
      if (debugScan) System.out.println("  adding " + myf.file.getAbsolutePath() + " forecast date= " + myf.dateCoordS +"("+myf.dateCoord+")"
              + " run date= " + formatter.toDateTimeStringISO(myf.runDate));
      runDatasets.add( ds);
      if (typicalDataset == null)
        typicalDataset = ds;
    }

    // open a "typical" dataset and make a GridDataset
    NetcdfFile typicalFile =  typicalDataset.acquireFile( cancelTask);
    NetcdfDataset typicalDS = (typicalFile instanceof NetcdfDataset) ? (NetcdfDataset) typicalFile : new NetcdfDataset( typicalFile);
    if (!typicalDS.isEnhanced())
      typicalDS.enhance();
    GridDataset gds = new ucar.nc2.dt.grid.GridDataset(typicalDS);

    // find the one time axis
    List grids = gds.getGrids();
    for (int i = 0; i < grids.size(); i++) {
      GridDatatype grid = (GridDatatype) grids.get(i);
      GridCoordSystem gcc = grid.getCoordinateSystem();
      timeAxis = gcc.getTimeAxis1D();
      if (null != timeAxis)
        break;
    }

    if (timeAxis == null)
      throw new IllegalStateException("No time variable");

    // loop over the runs; each becomes a nested dataset
    runs = new ArrayList( runHash.keySet());
    Collections.sort(runs);
    Iterator iter = runs.iterator();
    while (iter.hasNext()) {
      Date runDate = (Date) iter.next();
      String runDateS = formatter.toDateTimeStringISO(runDate);

      List runDatasets = (List) runHash.get( runDate);
      max_times = Math.max( max_times, runDatasets.size());

      // within each list, sort the datasets by time coordinate
      Collections.sort(runDatasets, new Comparator() {
        public int compare(Object o1, Object o2) {
          Dataset mf1 = (Dataset) o1;
          Dataset mf2 = (Dataset) o2;
          return mf1.coordValueDate.compareTo(mf2.coordValueDate);
        }
      });

      // create the dataset wrapping this run, each is 1 runtime coordinate of the outer aggregation
      NetcdfDataset ncd = new NetcdfDataset();
      ncd.setLocation("Run"+runDateS);
      DateFormatter format = new DateFormatter();
      if (debugScan) System.out.println("Run" + format.toDateTimeString(runDate));

      AggregationExisting agg = new AggregationExisting(ncd, timeAxis.getName(), null); // LOOK: dim name, existing vs new ??
      for (int i = 0; i < runDatasets.size(); i++) {
        Dataset dataset = (Dataset) runDatasets.get(i);
        agg.addDataset( dataset);
        if (debugScan) System.out.println("  adding Forecast " + format.toDateTimeString(dataset.coordValueDate)+" "+dataset.getLocation());
      }
      ncd.setAggregation( agg);
      agg.finish( cancelTask);

      nestedDatasets.add( new OpenDataset(ncd, runDate, runDateS));
    }

    return gds;
  }

  private Date addHour( Date d, double hour) {
    cal.setTime( d);

    int ihour = (int) hour;
    int imin = (int) (hour - ihour) * 60;
    cal.add(Calendar.HOUR_OF_DAY, ihour);
    cal.add(Calendar.MINUTE, imin);
    return cal.getTime();
  }

  // used in buildDataset
   protected Dataset getTypicalDataset() throws IOException {
     return typicalDataset;
  }

  // for the case that we dont have a fmrcDefinition
  protected void makeTimeCoordinate(GridDataset gds, CancelTask cancelTask) throws IOException {
    String innerDimName = timeAxis.getName();
    Dimension innerDim = new Dimension(innerDimName, max_times, true);
    ncDataset.removeDimension(null, innerDimName); // remove previous declaration, if any
    ncDataset.addDimension(null, innerDim);

    int[] shape = new int[] { runs.size(), max_times};
    Array timeCoordVals = Array.factory(timeAxis.getDataType(), shape);
    Index ima = timeCoordVals.getIndex();

    // loop over the runs, calculate the offset for each dataset
    Date baseDate = null;
    for (int i = 0; i < runs.size(); i++) {
      Date runDate = (Date) runs.get(i);
      if (baseDate == null) baseDate = runDate;

      List runDatasets = (List) runHash.get( runDate);
      for (int j = 0; j < runDatasets.size(); j++) {
        Dataset dataset = (Dataset) runDatasets.get(j);
        double offset = ForecastModelRunInventory.getOffsetInHours(baseDate, dataset.coordValueDate);
        timeCoordVals.setDouble(ima.set(i, j), offset);
      }
    }

    // construct new variable, replace old one, set values
    String dims = dimName + " " + innerDimName;
    String units = "hours since "+ formatter.toDateTimeStringISO(baseDate);
    String desc = "calculated forecast date from AggregationFmrcSingle processing";
    VariableDS vagg = new VariableDS(ncDataset, null, null, innerDimName, timeAxis.getDataType(), dims, units, desc);
    vagg.setCachedData(timeCoordVals, false);
    NcMLReader.transferVariableAttributes(timeAxis, vagg);
    vagg.addAttribute(new Attribute("units", units));
    vagg.addAttribute(new Attribute("long_name", desc));

    ncDataset.removeVariable(null, vagg.getName());
    ncDataset.addVariable(null, vagg);

    if (debug) System.out.println("FmrcAggregation: promoted timeCoord " + innerDimName);
  }


  /* protected void buildDataset(boolean isNew, CancelTask cancelTask) throws IOException {
    /* buildCoords(cancelTask);

    // open a "typical"  nested dataset and copy it to newds
    Dataset typicalDataset = getTypicalDataset();
    NetcdfFile typical =  typicalDataset.acquireFile(null);
    NcMLReader.transferDataset(typical, ncDataset, null); // isNew ? null : new MyReplaceVariableCheck());

    // some additional global attributes
    Group root = ncDataset.getRootGroup();
    root.addAttribute(new Attribute("Conventions", "CF-1.0, "+ _Coordinate.Convention));
    root.addAttribute(new Attribute("cdm_data_type", thredds.catalog.DataType.GRID.toString()));

    // create runtime aggregation dimension
    String dimName = getDimensionName();
    int nruns = getTotalCoords(); // same as  nestedDatasets.size()
    Dimension aggDim = new Dimension(dimName, nruns, true);
    ncDataset.removeDimension(null, dimName); // remove previous declaration, if any
    ncDataset.addDimension(null, aggDim);

    // create runtime aggregation coordinate variable
    DataType  coordType = getCoordinateType();
    VariableDS  runtimeCoordVar = new VariableDS(ncDataset, null, null, dimName, coordType, dimName, null, null);
    runtimeCoordVar.addAttribute(new Attribute("long_name", "Run time for ForecastModelRunCollection"));
    runtimeCoordVar.addAttribute(new ucar.nc2.Attribute("standard_name", "forecast_reference_time"));
    runtimeCoordVar.addAttribute(new ucar.nc2.Attribute(_Coordinate.AxisType, AxisType.RunTime.toString()));
    ncDataset.addVariable(null, runtimeCoordVar);
    if (debug) System.out.println("FmrcAggregation: added runtimeCoordVar " + runtimeCoordVar.getName());

    // add its data
    if (true) { // LOOK detect if we have the info
      ArrayObject.D1 runData = (ArrayObject.D1) Array.factory(DataType.STRING, new int[] {nruns});
      for (int j = 0; j < nestedDatasets.size(); j++) {
        Dataset dataset = (Dataset) nestedDatasets.get(j);
        runData.set(j, dataset.getCoordValueString());
      }
      runtimeCoordVar.setCachedData(runData, true);
    } else {
      runtimeCoordVar.setProxyReader( this);
    }

    // work with a GridDataset
    NetcdfDataset typicalDS;
    if (typical instanceof NetcdfDataset)
      typicalDS = (NetcdfDataset) typical;
    else
      typicalDS = new NetcdfDataset( typical);
    if (!typicalDS.isEnhanced())
      typicalDS.enhance();

    GridDataset gds = new ucar.nc2.dt.grid.GridDataset(typicalDS);

    // handle the 2D time coordinates and dimensions
    // for the case that we have a fmrcDefinition, there may be time coordinates that dont show up in the typical dataset
    if (fmrcDefinition != null) {

      List runSeq = fmrcDefinition.getRunSequences();
      for (int i = 0; i < runSeq.size(); i++) { // each runSeq generates a 2D time coordinate
        FmrcDefinition.RunSeq seq = (FmrcDefinition.RunSeq) runSeq.get(i);
        String timeDimName = seq.getName();

        // whats the maximum size ?
        boolean isRagged = false;
        int max_times = 0;
        for (int j = 0; j < nestedDatasets.size(); j++) {
          Dataset dataset = (Dataset) nestedDatasets.get(j);
          ForecastModelRunInventory.TimeCoord timeCoord = seq.findTimeCoordByRuntime(dataset.getCoordValueDate());
          double[] offsets = timeCoord.getOffsetHours();
          max_times = Math.max(max_times, offsets.length);
          if (max_times != offsets.length)
            isRagged = true;
        }

        // create time dimension
        Dimension timeDim = new Dimension(timeDimName, max_times, true);
        ncDataset.removeDimension(null, timeDimName); // remove previous declaration, if any
        ncDataset.addDimension(null, timeDim);

        Dataset firstDataset = (Dataset) nestedDatasets.get(0);
        Date baseDate = firstDataset.getCoordValueDate();
        String desc = "Coordinate variable for " + timeDimName + " dimension";
        String units = "hours since " + formatter.toDateTimeStringISO(baseDate);

        String dims = getDimensionName() + " " + timeDimName;
        Variable newV = new VariableDS(ncDataset, null, null, timeDimName, DataType.DOUBLE, dims, desc, units);

        // do we already have the coordinate variable ?
        Variable oldV = ncDataset.getRootGroup().findVariable(timeDimName);
        if (null != oldV) {
          //NcMLReader.transferVariableAttributes(oldV, newV);
          //Attribute att = newV.findAttribute(_Coordinate.AliasForDimension);  // ??
          //if (att != null) newV.remove(att);
          ncDataset.removeVariable(null, timeDimName);
        }
        ncDataset.addVariable(null, newV);

        newV.addAttribute(new Attribute("units", units));
        newV.addAttribute(new Attribute("long_name", desc));
        newV.addAttribute(new Attribute("standard_name", "time"));
        newV.addAttribute(new ucar.nc2.Attribute(_Coordinate.AxisType, AxisType.Time.toString()));
        if (isRagged)
          newV.addAttribute(new ucar.nc2.Attribute("missing_value", new Double(Double.NaN)));

        // compute the coordinates
        Array coordValues = calcTimeCoordinateFromDef(nruns, max_times, seq);
        newV.setCachedData(coordValues, true);
      }

      ncDataset.finish();

    } else {
      // for the case that we dont have a fmrcDefinition

      // LOOK how do we set the length of the time dimension(s), if its ragged?
      // Here we are just using the typical dataset !!!
      // For now, we dont handle ragged time coordinates.

      // find time axes
      HashSet timeAxes = new HashSet();
      List grids = gds.getGrids();
      for (int i = 0; i < grids.size(); i++) {
        GridDatatype grid = (GridDatatype) grids.get(i);
        GridCoordSystem gcc = grid.getCoordinateSystem();
        CoordinateAxis1D timeAxis = gcc.getTimeAxis1D();
        if (null != timeAxis)
          timeAxes.add(timeAxis);
      }

     // promote the time coordinate(s) to 2D, read in values if we have to
      Iterator iter = timeAxes.iterator();
      while( iter.hasNext()) {
        CoordinateAxis1DTime v = (CoordinateAxis1DTime) iter.next();

        // construct new variable, replace old one
        String dims = dimName + " " + v.getDimensionsString();
        VariableDS vagg = new VariableDS(ncDataset, null, null, v.getShortName(), v.getDataType(), dims, null, null);
        NcMLReader.transferVariableAttributes(v, vagg);
        Attribute att = vagg.findAttribute(_Coordinate.AliasForDimension);
        if (att != null) vagg.remove(att);

        ncDataset.removeVariable(null, v.getShortName());
        ncDataset.addVariable(null, vagg);

        if (!timeUnitsChange)
          // Case 1: assume the units are all the same, so its just another agg variable
          vagg.setProxyReader(this);
        else {
          // Case 2: assume the time units differ for each nested file
          readTimeCoordinates( vagg, cancelTask);
        }

        if (debug) System.out.println("FmrcAggregation: promoted timeCoord " + v.getName());
        if (cancelTask != null && cancelTask.isCancel()) return;
      }
    }

    // promote all grid variables
    List grids = gds.getGrids();
    for (int i = 0; i < grids.size(); i++) {
      GridDatatype grid = (GridDatatype) grids.get(i);
      Variable v = (Variable) grid.getVariable();

      // add new dimension
      String dims = dimName + " " + v.getDimensionsString();

      // construct new variable, replace old one
      VariableDS vagg = new VariableDS(ncDataset, null, null, v.getShortName(), v.getDataType(), dims, null, null);
      vagg.setProxyReader(this);
      NcMLReader.transferVariableAttributes(v, vagg);

      // we need to explicitly list the coordinate axes, because time coord is now 2D
      vagg.addAttribute(new Attribute(_Coordinate.Axes, dimName + " " + grid.getCoordinateSystem().getName()));
      vagg.addAttribute(new Attribute("coordinates", dimName + " " + grid.getCoordinateSystem().getName())); // CF

      ncDataset.removeVariable(null, v.getShortName());
      ncDataset.addVariable(null, vagg);
      if (debug) System.out.println("FmrcAggregation: added grid " + v.getName());
    }

    ncDataset.finish();
    makeProxies(typicalDataset, ncDataset);
    ncDataset.enhance();

    typical.close();
  }                  */

  /**
   * Encapsolates a NetcdfFile that is a component of the aggregation.
   * public for NcMLWriter
   */
  public class OpenDataset extends Dataset {

    private NetcdfFile openFile;

    /**
     * Dataset constructor with an opened NetcdfFile.
     * Used in nested aggregations like scanFmrc.
     */
    protected OpenDataset(NetcdfFile openFile, Date coordValueDate, String coordValue) {
      super(openFile.getLocation());
      this.openFile = openFile;
      this.ncoord = 1;
      this.coordValueDate = coordValueDate;
      this.coordValue = coordValue;
    }

    protected NetcdfFile acquireFile(CancelTask cancelTask) throws IOException {
      return openFile;
    }

    protected void close() throws IOException {
      openFile.close(); // LOOK dunno
    }

  }

}
