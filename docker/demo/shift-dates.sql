-- Moves all transaction and purchase dates of the demo data by the same amount, so that the newest
-- purchase is from an hour ago. The app marks users without a purchase in the last 180 days
-- (setting DAYS_BEFORE_INACTIVITY) as inactive, so this keeps the demo users active. Run by
-- `make db-demo` after the import.
SET @shift = TIMESTAMPDIFF(SECOND, (SELECT MAX(date) FROM Transaction), NOW() - INTERVAL 1 HOUR);
UPDATE Transaction SET date = date + INTERVAL @shift SECOND;
UPDATE Purchase SET createDate = createDate + INTERVAL @shift SECOND;
UPDATE ShoppingItem SET createDate = createDate + INTERVAL @shift SECOND;
