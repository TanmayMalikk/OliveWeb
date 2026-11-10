// The QR opens the permanent download page, which then serves the newest signed APK.
import QRCode from "qrcode";
import { fileURLToPath } from "node:url";

await QRCode.toFile(
  fileURLToPath(new URL("../public/android-download-qr.svg", import.meta.url)),
  "https://yourolivebranch.ca/get",
  {
    type: "svg",
    errorCorrectionLevel: "H",
    margin: 2,
    color: { dark: "#294224", light: "#ffffff" },
  },
);
