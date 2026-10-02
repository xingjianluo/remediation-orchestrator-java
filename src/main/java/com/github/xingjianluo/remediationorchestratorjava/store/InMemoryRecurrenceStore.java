package com.github.xingjianluo.remediationorchestratorjava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.remediationorchestratorjava.model.TargetRecurrence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 进程内复发存储，供单元测试使用（不落盘）。返回深拷贝以模拟持久化语义。 */
public class InMemoryRecurrenceStore implements RecurrenceStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, TargetRecurrence> records = new LinkedHashMap<>();

    @Override
    public synchronized TargetRecurrence getOrCreate(String target) {
        return copy(records.computeIfAbsent(target, t -> {
            TargetRecurrence r = new TargetRecurrence();
            r.target = t;
            return r;
        }));
    }

    @Override
    public synchronized TargetRecurrence save(TargetRecurrence record) {
        records.put(record.target, copy(record));
        return record;
    }

    @Override
    public synchronized Optional<TargetRecurrence> find(String target) {
        return Optional.ofNullable(records.get(target)).map(InMemoryRecurrenceStore::copy);
    }

    @Override
    public synchronized List<TargetRecurrence> findAll() {
        return records.values().stream().map(InMemoryRecurrenceStore::copy).toList();
    }

    private static TargetRecurrence copy(TargetRecurrence record) {
        try {
            return MAPPER.readValue(MAPPER.writeValueAsString(record), TargetRecurrence.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
