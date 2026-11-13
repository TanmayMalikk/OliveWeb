// Public visitors: download the signed Android release or install the iOS web app.
import {
  ArrowLeft, ArrowUpRight, Download, Globe, PlusSquare, ScanLine, Share, Smartphone,
} from "lucide-react";
import { Logo, External } from "../components/SharedComponents";
import { siteHref } from "../sites/SiteRouting";
import config from "../../site.config.json";

const androidDownload = "/download/android";

export default function DownloadPage() {
  return (
    <div className="access-page install-page">
      <header className="access-header">
        <Logo small />
        <a className="text-link" href={siteHref("/account/sign-in")}>
          Sign in <ArrowUpRight size={16} aria-hidden="true" />
        </a>
      </header>
      <main className="install-main" id="main">
        <a className="text-link" href={siteHref("/")}>
          <ArrowLeft size={16} aria-hidden="true" /> Back to Little Grove
        </a>
        <div className="install-heading">
          <h1>Get Olive Branch</h1>
          <p>Install on Android, add Little Grove to your iPhone or iPad, or use Olive Branch in your browser.</p>
        </div>
        <div className="install-platforms">
          <section className="access-card install-platform install-android" aria-labelledby="android-install">
            <span className="access-mark"><Download size={24} aria-hidden="true" /></span>
            <h2 id="android-install">Android</h2>
            <p>Download the latest signed release and open the APK on your Android device to install it.</p>
            <a className="button" href={androidDownload}>
              <Download size={17} aria-hidden="true" /> Download signed APK
            </a>
            <p className="install-note">Android may ask you to allow your browser to install apps from outside the Play Store.</p>
            <div className="install-qr-panel">
              <a className="install-qr" href={androidDownload} aria-label="Download signed Android APK">
                <img src="/android-download-qr.svg?v=direct-apk-v2" width={128} height={128}
                  alt="QR code for the latest signed Android app" />
              </a>
              <div>
                <h3><ScanLine size={17} aria-hidden="true" /> On another device?</h3>
                <p>Scan the code with your Android phone to download the APK there.</p>
              </div>
            </div>
          </section>
          <section className="access-card install-platform" aria-labelledby="ios-install">
            <span className="access-mark"><Smartphone size={24} aria-hidden="true" /></span>
            <h2 id="ios-install">iPhone and iPad</h2>
            <p>Add Little Grove to your Home Screen from Safari.</p>
            <ol className="install-guide">
              <li><Globe size={18} aria-hidden="true" /><h3>Open <a href={siteHref("/")}>Little Grove</a> in Safari</h3></li>
              <li><Share size={18} aria-hidden="true" /><h3>Tap Share in Safari</h3></li>
              <li><PlusSquare size={18} aria-hidden="true" /><div><h3>Choose Add to Home Screen</h3><p>Turn on “Open as Web App” if offered, then tap Add.</p></div></li>
            </ol>
            <p className="install-note">Keep your personal entry link separately so you can return to Safe Space.</p>
            <External href="https://support.apple.com/guide/iphone/turn-a-website-into-an-app-iphea86e5236/ios">
              Apple’s installation guide
            </External>
          </section>
        </div>
        <section className="access-card install-browser" aria-labelledby="browser-install">
          <span className="access-mark"><Globe size={24} aria-hidden="true" /></span>
          <div>
            <h2 id="browser-install">Use Olive Branch in your browser</h2>
            <p>Sign in with your existing account or create one to get started.</p>
          </div>
          <div className="install-browser-actions">
            <a className="button" href={new URL("/account/sign-in", config.publicOrigin).href}>
              Open the web app <ArrowUpRight size={17} aria-hidden="true" />
            </a>
            <a className="text-link" href={siteHref("/account/create")}>Create an account</a>
          </div>
        </section>
      </main>
    </div>
  );
}
