/*
 * Copyright (C) 2017 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.store.hbase;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.util.List;

import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.ParseFilter;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.protobuf.generated.FilterProtos;
import org.apache.hadoop.hbase.util.Bytes;

import com.google.common.collect.Lists;

public class HBaseUtils {
  static final ParseFilter FILTER_PARSEER = new ParseFilter();

  static final int FIRST_FILTER = 0;
  static final int LAST_FILTER = -1;

  public static byte[] getBytes(String str) {
    return str == null ? HConstants.EMPTY_BYTE_ARRAY : Bytes.toBytes(str);
  }

  static String keyToStr(byte[] key) {
    if(key == null || key.length == 0) {
      return null;
    }
    return Bytes.toStringBinary(key);
  }

  static String filterToStr(byte[] key) {
    if(key == null) {
      return null;
    }

    return deserializeFilter(key).toString();
  }

  static Filter parseFilterString(String filterString) {
    if (filterString == null) {
      return null;
    }
    try {
      return FILTER_PARSEER.parseFilterString(filterString);
    } catch (CharacterCodingException e) {
      throw new RuntimeException("Error parsing filter string: " + filterString, e);
    }
  }

  public static byte[] serializeFilter(Filter filter) {
    if (filter == null) {
      return null;
    }
    try {
      FilterProtos.Filter pbFilter = ProtobufUtil.toFilter(filter);
      return pbFilter.toByteArray();
    } catch (IOException e) {
      throw new RuntimeException("Error serializing filter: " + filter, e);
    }
  }

  public static Filter deserializeFilter(byte[] filterBytes) {
    if (filterBytes == null) {
      return null;
    }
    try {
      FilterProtos.Filter pbFilter = FilterProtos.Filter.parseFrom(filterBytes);
      return ProtobufUtil.toFilter(pbFilter);
    } catch (Exception e) {
      throw new RuntimeException("Error deserializing filter: " + filterBytes, e);
    }
  }

  public static Filter andFilterAtIndex(Filter currentFilter, int index, Filter newFilter) {
    if (currentFilter == null) {
      return newFilter;
    } else if (newFilter == null) {
      return currentFilter;
    }

    List<Filter> allFilters = Lists.newArrayList();
    if (currentFilter instanceof FilterList && ((FilterList)currentFilter).getOperator() == FilterList.Operator.MUST_PASS_ALL) {
      allFilters.addAll(((FilterList)currentFilter).getFilters());
    } else {
      allFilters.add(currentFilter);
    }
    allFilters.add((index == LAST_FILTER ? allFilters.size() : index), newFilter);
    return new FilterList(FilterList.Operator.MUST_PASS_ALL, allFilters);
  }

  public static Filter orFilterAtIndex(Filter currentFilter, int index, Filter newFilter) {
    if (currentFilter == null) {
      return newFilter;
    } else if (newFilter == null) {
      return currentFilter;
    }

    List<Filter> allFilters = Lists.newArrayList();
    if (currentFilter instanceof FilterList && ((FilterList)currentFilter).getOperator() == FilterList.Operator.MUST_PASS_ONE) {
      allFilters.addAll(((FilterList)currentFilter).getFilters());
    } else {
      allFilters.add(currentFilter);
    }
    allFilters.add((index == LAST_FILTER ? allFilters.size() : index), newFilter);
    return new FilterList(FilterList.Operator.MUST_PASS_ONE, allFilters);
  }

  public static byte[] maxOfStartRows(byte[] left, byte[] right) {
    if (left == null || left.length == 0 || right == null || right.length == 0) {
      return (left == null || left.length == 0) ? right : left;
    }
    return Bytes.compareTo(left, right) > 0 ? left : right;
  }

  public static byte[] minOfStartRows(byte[] left, byte[] right) {
    if (left == null || left.length == 0 || right == null || right.length == 0) {
      return HConstants.EMPTY_BYTE_ARRAY;
    }
    return Bytes.compareTo(left, right) < 0 ? left : right;
  }

  public static byte[] maxOfStopRows(byte[] left, byte[] right) {
    if (left == null || left.length == 0 || right == null || right.length == 0) {
      return HConstants.EMPTY_BYTE_ARRAY;
    }
    return Bytes.compareTo(left, right) > 0 ? left : right;
  }

  public static byte[] minOfStopRows(byte[] left, byte[] right) {
    if (left == null || left.length == 0 || right == null || right.length == 0) {
      return (left == null || left.length == 0) ? right : left;
    }
    return Bytes.compareTo(left, right) < 0 ? left : right;
  }

  /**
   * @param left left rowkey
   * @param right right rowkey
   * @return 0 if equal, < 0 if left is less than right, > 0 if left is greater than right
   */
  public static int compareRowKeys(byte[] left, byte[] right) {
    return Bytes.compareTo(left, right);
  }

  /**
   * Check if a rowkey is empty
   */
  public static boolean isRowKeyEmpty(byte[] stoprow) {
    return stoprow.length == 0;
  }

}
