import HistoryApp from "./HistoryApp";
import SettingsApp from "./SettingsApp";
import TrayPopover from "./TrayPopover";

export default function App() {
  const view = new URLSearchParams(window.location.search).get("view");

  if (view === "tray") {
    return <TrayPopover />;
  }

  if (view === "settings") {
    return <SettingsApp />;
  }

  return <HistoryApp />;
}
