// The QR code encodes the direct download URL for the newest signed APK.
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
