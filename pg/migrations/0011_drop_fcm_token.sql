-- 0011_drop_fcm_token.sql
-- Remove the unused fcm_token column from users.
--
-- The FCM push/delete pipeline was dead end-to-end: the server's
-- service-account.json was only a placeholder, the Android-side
-- handlePush() was a stub, and even the field name mismatched between
-- LoginRequest.fcm (client) and request.json['fcmToken'] (server) so
-- no token was ever stored in the first place. With /push and /delete
-- removed and the Android Firebase deps gone, the column has no
-- producer or consumer.

ALTER TABLE users DROP COLUMN IF EXISTS fcm_token;
