/*
 Copyright 2008-2012 Gephi
 Authors : Martin Škurla <bujacik@gmail.com>, Mathieu Bastian <mathieu.bastian@gephi.org>, Eduardo Ramos<eduramiba@gmail.com>
 Website : http://www.gephi.org

 This file is part of Gephi.

 DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.

 Copyright 2011 Gephi Consortium. All rights reserved.

 The contents of this file are subject to the terms of either the GNU
 General Public License Version 3 only ("GPL") or the Common
 Development and Distribution License("CDDL") (collectively, the
 "License"). You may not use this file except in compliance with the
 License. You can obtain a copy of the License at
 http://gephi.org/about/legal/license-notice/
 or /cddl-1.0.txt and /gpl-3.0.txt. See the License for the
 specific language governing permissions and limitations under the
 License.  When distributing the software, include this License Header
 Notice in each file and include the License files at
 /cddl-1.0.txt and /gpl-3.0.txt. If applicable, add the following below the
 License Header, with the fields enclosed by brackets [] replaced by
 your own identifying information:
 "Portions Copyrighted [year] [name of copyright owner]"

 If you wish your version of this file to be governed by only the CDDL
 or only the GPL Version 3, indicate your decision by adding
 "[Contributor] elects to include this software in this distribution
 under the [CDDL or GPL Version 3] license." If you do not indicate a
 single choice of license, a recipient has the option to distribute
 your version of this file under either the CDDL, the GPL Version 3 or
 to extend the choice of license to its licensees as provided above.
 However, if you add GPL Version 3 code and therefore, elected the GPL
 Version 3 license, then the option applies only if the new code is
 made subject to such option by the copyright holder.

 Contributor(s):

 Portions Copyrighted 2011 Gephi Consortium.
 */
package org.gephi.datalab.utils;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import org.gephi.attribute.api.AttributeUtils;
import org.gephi.attribute.time.Interval;
import org.gephi.attribute.time.IntervalWithValue;

/**
 * <p>
 * Class for parsing dynamic types with several intervals.</p>
 *
 * <p>
 * Examples of valid dynamic intervals are:
 * <ul>
 * <li>&lt;(1, 2, v1); [3, 5, v2]&gt;</li>
 * <li>[1,2]</li>
 * <li>[1,2] (5,6)</li>
 * <li>[1,2]; [1.15,2.21, 'literal value " \' ,[]()']</li>
 * <li>&lt;[1,2]; [1.15,2.21, "literal value \" ' ,[]()"]&gt;</li>
 * </ul>
 * </p>
 *
 * <p>
 * The most correct examples are those that include &lt; &gt; and proper commas and semicolons for separation, but the parser will be permissive when possible.</p>
 * <p>
 * Gephi will always format intervals in the correct way.</p>
 *
 * <p>
 * See https://gephi.org/users/supported-graph-formats/spreadsheet for more examples</p>
 *
 * @author Eduardo Ramos<eduramiba@gmail.com>
 */
public final class DynamicParser {

    private static final char LOPEN = '(';
    private static final char LCLOSE = '[';
    private static final char ROPEN = ')';
    private static final char RCLOSE = ']';
    private static final char COMMA = ',';

    /**
     * Parses a dynamic type with one or more intervals (without values, only start and end)
     *
     * @param input Input string to parse
     * @return List of parsed intervals, or null if the input equals '<empty>'
     * @throws IOException Thrown if error while reading input
     * @throws ParseException Thronw if the intervals could not be parsed
     */
    public static List<Interval> parseIntervals(String input) throws IOException, ParseException, IllegalArgumentException {
        return parseIntervals(null, input);
    }

    /**
     * Parses a dynamic type with one or more intervals, with associated values
     *
     * @param <T>
     * @param clazz Simple type for the result intervals values
     * @param input Input string to parse
     * @return List of parsed intervals, or null if the input equals '<empty>'
     * @throws IOException Thrown if error while reading input
     * @throws ParseException Thronw if the intervals could not be parsed
     */
    public static <T> List<IntervalWithValue<T>> parseIntervalsWithValues(Class clazz, String input) throws IOException, ParseException, IllegalArgumentException {
        List<Interval> intervals = parseIntervals(clazz, input);

        List<IntervalWithValue<T>> result = new ArrayList<IntervalWithValue<T>>();

        for (Interval interval : intervals) {
            result.add((IntervalWithValue<T>) interval);
        }

        return result;
    }

    public static List<Interval> parseIntervals(Class type, String input) throws IOException, ParseException, IllegalArgumentException {
        if (input.equalsIgnoreCase("<empty>")) {
            return null;
        }

        List<Interval> intervals = new ArrayList<Interval>();

        StringReader reader = new StringReader(input + ' ');//Add 1 space so reader.skip function always works when necessary (end of string not reached).

        int r;
        char c;
        while ((r = reader.read()) != -1) {
            c = (char) r;
            switch (c) {
                case LCLOSE:
                case LOPEN:
                    intervals.add(parseInterval(type, reader, c == LOPEN));
                    break;
                default:
                //Ignore other chars outside of intervals
            }
        }

        if (intervals.isEmpty()) {
            throw new IllegalArgumentException("No dynamic intervals could be parsed");
        }

        return intervals;
    }

    private static Interval parseInterval(Class type, StringReader reader, boolean lopen) throws IOException, ParseException {
        ArrayList<String> values = new ArrayList<String>();
        boolean ropen = true;

        int r;
        char c;
        while ((r = reader.read()) != -1) {
            c = (char) r;
            switch (c) {
                case RCLOSE:
                    ropen = false;
                case ROPEN:
                    return buildInterval(type, values, lopen, ropen);
                case ' ':
                case '\t':
                case '\r':
                case '\n':
                case COMMA:
                    //Ignore leading whitespace or similar until a value or literal starts:
                    break;
                case '"':
                case '\'':
                    values.add(parseLiteral(reader, c));
                    break;
                default:
                    reader.skip(-1);//Go backwards 1 position, for reading start of value
                    values.add(parseValue(reader));
            }
        }

        return buildInterval(type, values, lopen, ropen);
    }

    /**
     * Parse literal value until detecting the end of it (quote can be ' or ")
     *
     * @param reader Input reader
     * @param quote Quote mode that started this literal (' or ")
     * @return Parsed value
     * @throws IOException
     */
    private static String parseLiteral(StringReader reader, char quote) throws IOException {
        StringBuilder sb = new StringBuilder();
        boolean escapeEnabled = false;

        int r;
        char c;
        while ((r = reader.read()) != -1) {
            c = (char) r;
            if (c == quote) {
                if (escapeEnabled) {
                    sb.append(quote);
                    escapeEnabled = false;
                } else {
                    return sb.toString();
                }
            } else {
                switch (c) {
                    case '\\':
                        if (escapeEnabled) {
                            sb.append('\\');

                            escapeEnabled = false;
                        } else {
                            escapeEnabled = true;
                        }
                        break;
                    default:
                        if (escapeEnabled) {
                            escapeEnabled = false;
                        }
                        sb.append(c);
                }
            }
        }

        return sb.toString();
    }

    /**
     * Parses a value until end is detected either by a comma or an interval closing character.
     *
     * @param reader Input reader
     * @return Parsed value
     * @throws IOException
     */
    private static String parseValue(StringReader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        int r;
        char c;
        while ((r = reader.read()) != -1) {
            c = (char) r;
            switch (c) {
                case ROPEN:
                case RCLOSE:
                    reader.skip(-1);//Go backwards 1 position, for detecting end of interval
                case COMMA:
                    return sb.toString().trim();
                default:
                    sb.append(c);
            }
        }

        return sb.toString().trim();
    }

    private static Interval buildInterval(Class type, ArrayList<String> values, boolean lopen, boolean ropen) throws ParseException {
        double low, high;

        low = parseTime(values.get(0));
        high = parseTime(values.get(1));

        if (type == null) {
            return new Interval(low, high, lopen, ropen);
        } else {
            Object value = null;
            if (values.size() == 3) {
                //Interval with value:
                String valString = values.get(2);
                if (type.equals(Byte.class)
                        || type.equals(Short.class)
                        || type.equals(Integer.class)
                        || type.equals(Long.class)
                        || type.equals(BigInteger.class)
                        ) {
                    valString = removeDecimalDigitsFromString(valString);
                } else if (type.equals(Float.class)
                        || type.equals(Double.class)
                        || type.equals(BigDecimal.class)
                        ) {
                    valString = infinityIgnoreCase(valString);
                }
                
                value = AttributeUtils.parse(valString, type);
            }
            
            return new IntervalWithValue(low, high, lopen, ropen, value);
        }
    }

    //For date parsing:
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static DatatypeFactory dateFactory;

    static {
        try {
            dateFactory = DatatypeFactory.newInstance();
        } catch (DatatypeConfigurationException ex) {
        }
    }

    //Throws exception when a date can't be parsed
    public static double getDoubleFromXMLDateString(String str) throws ParseException {
        try {
            return dateFactory.newXMLGregorianCalendar(str.length() > 23 ? str.substring(0, 23) : str).
                    toGregorianCalendar().getTimeInMillis();
        } catch (IllegalArgumentException ex) {
            //Try simple format
            Date date = dateFormat.parse(str);
            return date.getTime();
        }
    }

    public static double parseTime(String time) throws ParseException {
        double value;
        try {
            //Try first to parse as a single double:
            value = Double.parseDouble(infinityIgnoreCase(time));
            if (Double.isNaN(value)) {
                throw new IllegalArgumentException("NaN is not allowed as an interval bound");
            }
        } catch (Exception ex) {
            //Try to parse as date instead
            value = getDoubleFromXMLDateString(time);
        }

        return value;
    }

    /**
     * Method for allowing inputs such as "infinity" when parsing decimal numbers
     *
     * @param value Input String
     * @return Input String with fixed "Infinity" syntax if necessary.
     */
    private static String infinityIgnoreCase(String value) {
        if (value.equalsIgnoreCase("Infinity")) {
            return "Infinity";
        }
        if (value.equalsIgnoreCase("-Infinity")) {
            return "-Infinity";
        }

        return value;
    }

    /**
     * Removes the decimal digits and point of the numbers of string when necessary. Used for trying to parse decimal numbers as not decimal. For example BigDecimal to BigInteger.
     *
     * @param s String to remove decimal digits
     * @return String without dot and decimal digits.
     */
    private static String removeDecimalDigitsFromString(String s) {
        return removeDecimalDigitsFromStringPattern.matcher(s).replaceAll("");
    }
    private static final Pattern removeDecimalDigitsFromStringPattern = Pattern.compile("\\.[0-9]*");
}
