# Next steps

Implemented: Firebase username/password accounts with private per-account Firebase Storage profiles, required full name and DOB, optional affected names and situation, English/French interfaces, safe settings, PIN-protected trigger changes, 4–6 digit PINs, failed-attempt history, editable contacts, quick exit, saved delivery addresses, and checkout that always declines without persisting payment fields.

1. Test the complete connected flow on physical Pixel 10 and Pixel 9a devices, including accessibility, French layouts, interruption handling and quick exit.
2. Add further reviewed translations as needed; currently only English and French are offered.
3. Choose account recovery, data retention/deletion, multi-device conflict handling and remote device revocation. Username aliases cannot receive recovery emails.
4. Choose additional safe-area features collaboratively. Emergency messages, evidence collection and tracking have not been added.
5. Release-signing configuration and a signature/checksum packaging script are prepared (docs/DISTRIBUTION.md). Create the private release keystore, then arrange HTTPS hosting and QR distribution. Hosting is deferred at the user’s request; no public upload has occurred.
