// The QR always points to the stable download route; latest.json decides which signed APK it serves.
import QRCode from "qrcode";
import { fileURLToPath } from "node:url";

await QRCode.toFile(
  fileURLToPath(new URL("../public/android-download-qr.svg", import.meta.url)),
  "https://yourolivebranch.ca/download/android",
  {
    type: "svg",
    errorCorrectionLevel: "H",
    margin: 2,
    color: { dark: "#294224", light: "#ffffff" },
  },
);
