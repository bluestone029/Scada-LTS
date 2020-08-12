/*
 * (c) 2020 hyski.mateusz@gmail.com, kamil.jarmusik@gmail.com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.scada_lts.dao.migration.mysql;

import com.serotonin.mango.vo.DataPointVO;
import org.flywaydb.core.api.migration.spring.SpringJdbcMigration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.util.List;
import java.util.Objects;

/**
 *
 * @author kamil.jarmusik@gmail.com
 *
 */

public class V2_3__FaultsAndAlarms implements SpringJdbcMigration {

    public void migrate(JdbcTemplate jdbcTmp) throws Exception {

        try {
            addColumnsToDataPointsTable(jdbcTmp);
            updateDataPointsTable(jdbcTmp);
            createPlcAlarmsTable(jdbcTmp);
            createFunctions(jdbcTmp);
            createViews(jdbcTmp);
            createProcedure(jdbcTmp);
            createTrigger(jdbcTmp);
        } catch (Exception ex) {
            ex.printStackTrace();
            throw ex;
        }

    }

    private void addColumnsToDataPointsTable(JdbcTemplate jdbcTmp) throws Exception {

        //this additional column will contain ONLY data point name which trigger needs
        jdbcTmp.execute("ALTER TABLE dataPoints ADD pointName VARCHAR(250);");

        //this additional column will have defined level of alarm as a 0-8 steps.
        jdbcTmp.execute("ALTER TABLE dataPoints ADD plcAlarmLevel TINYINT(8);");

    }

    private void updateDataPointsTable(JdbcTemplate jdbcTmp) throws Exception {

        //Using DataPointVO forces deserialization
        List<DataPointVO> dataPoints = jdbcTmp.query("SELECT id, data FROM dataPoints", (resultSet, i) -> {
            try (InputStream inputStream = resultSet.getBinaryStream("data");
                 ObjectInputStream objectInputStream = new ObjectInputStream(inputStream)) {
                DataPointVO dataPointVO = (DataPointVO) objectInputStream.readObject();
                dataPointVO.setId(resultSet.getInt("id"));
                return dataPointVO;
            } catch (IOException | ClassNotFoundException ex) {
                ex.printStackTrace();
                return null;
            }
        });

        boolean isNull = dataPoints.stream().anyMatch(Objects::isNull);
        if(isNull) {
            throw new IllegalStateException("DataPointVO is null!");
        }

        for(DataPointVO dataPointPart: dataPoints) {
            String dataPointName = dataPointPart.getName();
            int plcAlarmLevel = 0;
            if(dataPointName.contains(" AL ")) {
                plcAlarmLevel = 2;
            }
            if(dataPointName.contains(" ST ")) {
                plcAlarmLevel = 1;
            }
            jdbcTmp.update("UPDATE dataPoints SET plcAlarmLevel = ?, pointName = ? WHERE id = ?",
                    plcAlarmLevel, dataPointName, dataPointPart.getId());
        }

    }

    private void createPlcAlarmsTable(JdbcTemplate jdbcTmp) throws Exception {

        jdbcTmp.execute("CREATE TABLE plcAlarms (\n" +
                "  id INT UNSIGNED NOT NULL AUTO_INCREMENT,\n" +
                "  dataPointId INT NOT NULL,\n" +
                "  dataPointXid VARCHAR(50) DEFAULT NULL,\n" +
                "  dataPointType VARCHAR(45) DEFAULT NULL,\n" +
                "  dataPointName VARCHAR(45) DEFAULT NULL,\n" +
                "  activeTime BIGINT DEFAULT 0,\n" +
                "  inactiveTime BIGINT DEFAULT 0,\n" +
                "  acknowledgeTime BIGINT DEFAULT 0,\n" +
                "  level TINYINT(8) DEFAULT NULL,\n" +
                "  PRIMARY KEY (id), \n" +
                "  FOREIGN KEY (dataPointId) REFERENCES dataPoints(id) ON DELETE CASCADE,\n" +
                "  UNIQUE(dataPointId, inactiveTime)\n" +
                ") ENGINE=InnoDB;");

    }

    private void createFunctions(JdbcTemplate jdbcTmp) {

        jdbcTmp.execute("CREATE FUNCTION func_fromats_date(ts BIGINT) RETURNS varchar(19) CHARSET utf8\n" +
                "BEGIN  \n" +
                "\tIF(ts = 0) THEN\n" +
                "\t\tRETURN ' ';\n" +
                "\tEND IF;\n" +
                "    \n" +
                "\tIF(ts <> 0) THEN\n" +
                "\t\tRETURN substring(from_unixtime(ts/1000),1,19);\n" +
                "\tEND IF;\n" +
                "END");
    }

    private static void createViews(JdbcTemplate jdbcTmp) throws Exception {


        jdbcTmp.execute("CREATE VIEW historyAlarms AS SELECT " +
                "func_fromats_date(inactiveTime) AS 'time',\n" +
                "level,\n" +
                "dataPointName AS 'name' \n" +
                "FROM plcAlarms ORDER BY inactiveTime DESC, id DESC;\n");

        jdbcTmp.execute("CREATE VIEW liveAlarms AS SELECT\n" +
                "  id,\n" +
                "  func_fromats_date(activeTime) AS 'activation-time',\n" +
                "  func_fromats_date(inactiveTime) AS 'inactivation-time',\n" +
                "  dataPointType AS 'level',\n" +
                "  dataPointName AS 'name'\n" +
                "FROM plcAlarms WHERE acknowledgeTime = 0\n" +
                "  AND (inactiveTime = 0 OR (inactiveTime > UNIX_TIMESTAMP(NOW() - INTERVAL 24 HOUR) * 1000))\n" +
                "ORDER BY inactiveTime = 0 DESC, activeTime DESC, inactiveTime DESC, id DESC;");

    }

    private void createProcedure(JdbcTemplate jdbcTmp) throws Exception {

        jdbcTmp.execute("CREATE PROCEDURE prc_alarms_notify(IN newDataPointId INT, IN newTs BIGINT, IN newPointValue VARCHAR(45))\n" +
                "BEGIN\n" +
                "\tDECLARE PLC_ALARM_LEVEL INT(1);\n" +
                "\tDECLARE PRESENT_POINT_VALUE INT(1);\n" +
                "\tDECLARE ACTUAL_ID_ROW INT UNSIGNED;\n" +
                "\tDECLARE IS_RISING_SLOPE BOOLEAN DEFAULT FALSE;\n" +
                "    DECLARE IS_FALLING_SLOPE BOOLEAN DEFAULT FALSE;\n" +
                "\n" +
                "\tSELECT plcAlarmLevel INTO PLC_ALARM_LEVEL FROM dataPoints WHERE id = newDataPointId;\n" +
                "    SELECT newPointValue INTO PRESENT_POINT_VALUE;\n" +
                "\n" +
                "\tIF (PLC_ALARM_LEVEL = 1 OR PLC_ALARM_LEVEl = 2) THEN\n" +
                "\n" +
                "\t\tSELECT id INTO ACTUAL_ID_ROW FROM plcAlarms WHERE\n" +
                "\t\t\tdataPointId = newDataPointId AND\n" +
                "            inactiveTime = 0;\n" +
                "\n" +
                "\t\tSET IS_RISING_SLOPE = PRESENT_POINT_VALUE = 1 AND ACTUAL_ID_ROW IS NULL;\n" +
                "        SET IS_FALLING_SLOPE = PRESENT_POINT_VALUE = 0 AND ACTUAL_ID_ROW IS NOT NULL;\n" +
                "\n" +
                "        IF (IS_RISING_SLOPE OR IS_FALLING_SLOPE) THEN\n" +
                "\t\t\tINSERT INTO plcAlarms (\n" +
                "\t\t\t\t\tdataPointId,\n" +
                "\t\t\t\t\tdataPointXid,\n" +
                "\t\t\t\t\tdataPointType,\n" +
                "\t\t\t\t\tdataPointName,\n" +
                "\t\t\t\t\tactiveTime,\n" +
                "\t\t\t\t\tinactiveTime,\n" +
                "\t\t\t\t\tacknowledgeTime,\n" +
                "\t\t\t\t\tlevel\n" +
                "\t\t\t\t)\n" +
                "\t\t\t\tVALUES (\n" +
                "\t\t\t\t\tnewDataPointId,\n" +
                "\t\t\t\t\t(SELECT xid FROM dataPoints WHERE id = newDataPointId),\n" +
                "\t\t\t\t\tPLC_ALARM_LEVEL,\n" +
                "\t\t\t\t\t(SELECT pointName FROM dataPoints WHERE id = newDataPointId),\n" +
                "\t\t\t\t\tnewTs,\n" +
                "\t\t\t\t\t0,\n" +
                "\t\t\t\t\t0,\n" +
                "\t\t\t\t\tPLC_ALARM_LEVEL\n" +
                "\t\t\t\t) ON DUPLICATE KEY UPDATE\n" +
                "\t\t\t\t\tinactiveTime = newTs;\n" +
                "\t\tEND IF;\n" +
                "\tEND IF;" +
                "END");

    }

    private void createTrigger(JdbcTemplate jdbcTmp) throws Exception {

        jdbcTmp.execute("CREATE TRIGGER tri_notify_faults_or_alarms AFTER INSERT ON pointValues \n" +
                "FOR EACH ROW CALL prc_alarms_notify(new.dataPointId, new.ts, new.pointValue);");

    }
}