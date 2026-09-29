package de.timowa.expenselog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The records ticked in the records list, by id. Kept by id rather than by position, so a list
 * that is reloaded or reordered underneath it keeps the same records selected.
 */
final class RecordSelection {

    private final Set<Integer> ids = new LinkedHashSet<>();

    boolean isActive() {
        return !ids.isEmpty();
    }

    int size() {
        return ids.size();
    }

    boolean contains(int id) {
        return ids.contains(id);
    }

    /** Selects the record if it was not selected, and deselects it if it was. */
    void toggle(int id) {
        if (!ids.remove(id))
            ids.add(id);
    }

    /**
     * Selects every record that is not a repeating entry.
     *
     * @return how many repeating entries were left out
     */
    int selectAllPlain(List<LogItem> records) {
        int skipped = 0;
        for (LogItem record : records) {
            if (record.getRepeatingId() > -1)
                skipped++;
            else
                ids.add(record.getId());
        }
        return skipped;
    }

    /** Whether a repeating entry is selected -- which, in the records list, means it alone is. */
    boolean holdsRepeating(List<LogItem> records) {
        if (records == null)
            return false;
        for (LogItem record : records)
            if (record.getRepeatingId() > -1 && ids.contains(record.getId()))
                return true;
        return false;
    }

    /** The selected records among {@code records}, in list order. */
    List<LogItem> selected(List<LogItem> records) {
        List<LogItem> selected = new ArrayList<>();
        for (LogItem record : records)
            if (ids.contains(record.getId()))
                selected.add(record);
        return selected;
    }

    void clear() {
        ids.clear();
    }

    /** Forgets any selected id that is no longer among {@code records}. */
    void retainAll(List<LogItem> records) {
        Set<Integer> present = new LinkedHashSet<>();
        for (LogItem record : records)
            present.add(record.getId());
        ids.retainAll(present);
    }

    List<Integer> ids() {
        return new ArrayList<>(ids);
    }

    int[] toArray() {
        int[] array = new int[ids.size()];
        int i = 0;
        for (int id : ids)
            array[i++] = id;
        return array;
    }

    void addAll(int[] selected) {
        for (int id : selected)
            ids.add(id);
    }

    /**
     * The selected records' balance: income added, expenses subtracted. Amounts are stored
     * unsigned, with {@code expenseIncome} 0 for an expense, so a selection of expenses alone
     * comes out negative, as the list's expense total does.
     */
    double total(List<LogItem> records) {
        double total = 0;
        for (LogItem record : records)
            if (ids.contains(record.getId()))
                total += record.getExpenseIncome() == 0 ? -record.getAmount() : record.getAmount();
        return total;
    }
}
