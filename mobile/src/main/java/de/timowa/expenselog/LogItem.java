package de.timowa.expenselog;

/**
 * Log item object
 */
public class LogItem {
    private int id;
    private long timeStamp;
    private double amount;
    private int category;
    private String notes;
    private String imageUri;
    private int accountId;
    private int expenseIncome;
    private int repeatingId;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public long getTimeStamp() {
        return timeStamp;
    }

    public void setTimeStamp(long timeStamp) {
        this.timeStamp = timeStamp;
    }

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public int getCategory() {
        return category;
    }

    public void setCategory(int category) {
        this.category = category;
    }

    public int getExpenseIncome() {
        return expenseIncome;
    }

    public void setExpenseIncome(int expenseIncome) {
        this.expenseIncome = expenseIncome;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getImageUri() {
        return imageUri;
    }

    public void setImageUri(String imageUri) {
        this.imageUri = imageUri;
    }

    public int getRepeatingId() {
        return repeatingId;
    }

    public void setRepeatingId(int repeatingId) {
        this.repeatingId = repeatingId;
    }

    public int getAccountId() {
        return accountId;
    }

    public void setAccountId(int accountId) {
        this.accountId = accountId;
    }
}