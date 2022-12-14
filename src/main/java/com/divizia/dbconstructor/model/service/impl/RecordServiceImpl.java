package com.divizia.dbconstructor.model.service.impl;

import com.divizia.dbconstructor.exceptions.RecordNotFoundException;
import com.divizia.dbconstructor.model.entity.CustomTable;
import com.divizia.dbconstructor.model.entity.Record;
import com.divizia.dbconstructor.model.enums.RequisiteType;
import com.divizia.dbconstructor.model.repo.CustomTableRepository;
import com.divizia.dbconstructor.model.service.IdChecker;
import com.divizia.dbconstructor.model.service.RecordService;
import com.divizia.dbconstructor.model.service.SubscriptionTaskService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
@Slf4j
public class RecordServiceImpl implements RecordService {

    private final CustomTableRepository customTableRepository;
    private final SubscriptionTaskService subscriptionTaskService;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public Record saveAndFlush(Record record) {
        record.setCustomTableId(IdChecker.checkId(record.getCustomTableId()));

        Long id = record.getId();
        String idString = id == null || id == 0 ? "default" : id.toString();

        StringBuilder columns = new StringBuilder("a_id");
        StringBuilder values = new StringBuilder(idString);
        StringBuilder conflict = new StringBuilder("a_update_time=EXCLUDED.a_update_time");
        if (record.getRequisiteValueMap() != null) {
            record.getRequisiteValueMap().forEach((x, y) -> {
                columns.append(",").append(x);
                values.append(",").append(RequisiteType.toStringForDB(y));
                conflict.append(",").append(x).append("=EXCLUDED.").append(x);
            });
        }

        String query = String.format(
                        "INSERT INTO %s(%s) VALUES (%s) ON CONFLICT (a_id) DO UPDATE SET %s RETURNING a_id, a_update_time",
                        record.getCustomTableId(),
                        columns,
                        values,
                        conflict);
        log.info(query);
        List<Map<String, Object>> mapList = jdbcTemplate.queryForList(query);
        Map<String, Object> result = mapList.get(0);

        Long savedId = (Long) result.get("a_id");

        Record savedRecord = findById(record.getCustomTableId(), savedId).orElseThrow(
                () -> new RecordNotFoundException(record.getCustomTableId(), savedId));

        subscriptionTaskService.subscribe(savedRecord.getCustomTableId(), savedRecord.getId());

        return savedRecord;
    }

    @Override
    public void deleteById(String customTableId, Long recordId) {
        customTableId = IdChecker.checkId(customTableId);

        String query = String.format("delete from %s where a_id = %s", customTableId, recordId);
        log.info(query);
        jdbcTemplate.execute(query);

        subscriptionTaskService.unsubscribe(customTableId, recordId);
    }

    @Override
    public Optional<Record> findById(String customTableId, Long recordId) {
        List<Record> records = findAllById(customTableId, List.of(recordId));

        Optional<Record> optionalRecord = Optional.empty();
        if (!records.isEmpty())
            optionalRecord = Optional.of(records.get(0));

        return optionalRecord;
    }

    @Override
    public List<Record> findAllById(String customTableId, Collection<Long> ids) {
        String condition = String.format("where a_id in (%s)",
                ids.stream().map(String::valueOf).collect(Collectors.joining(",")));

        return findAllWithCondition(customTableId, condition);
    }

    @Override
    public List<Record> findAll(String customTableId) {
        return findAllWithCondition(customTableId, "");
    }

    private List<Record> findAllWithCondition(String customTableId, String condition) {
        customTableId = IdChecker.checkId(customTableId);

        List<Record> recordList = new ArrayList<>();

        Optional<CustomTable> customTable = customTableRepository.findById(customTableId);
        if (customTable.isEmpty())
            return recordList;

        String query = String.format("select * from %s %s order by a_id", customTableId, condition);
        log.info(query);
        List<Map<String, Object>> queryForList = jdbcTemplate.queryForList(query);
        if (queryForList.isEmpty())
            return recordList;

        for (Map<String, Object> params : queryForList)
            recordList.add(createRecordFromEntry(customTable.get().getId(), params));

        return recordList;
    }

    private Record createRecordFromEntry(String customTableId, Map<String, Object> params) {
        Record record = new Record();
        record.setCustomTableId(customTableId);

        record.setId((Long) params.get("a_id"));
        record.setUpdateTime(((Timestamp) params.get("a_update_time")).toLocalDateTime());
        params.remove("a_id");
        params.remove("a_update_time");

        params.entrySet().forEach(x -> {
            Object valueObj = x.getValue();
            if (valueObj instanceof Timestamp)
                x.setValue(((Timestamp) valueObj).toLocalDateTime());
        });

        record.setRequisiteValueMap(params);
        return record;
    }

}
