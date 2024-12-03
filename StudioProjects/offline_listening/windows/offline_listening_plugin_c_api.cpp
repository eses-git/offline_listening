#include "include/offline_listening/offline_listening_plugin_c_api.h"

#include <flutter/plugin_registrar_windows.h>

#include "offline_listening_plugin.h"

void OfflineListeningPluginCApiRegisterWithRegistrar(
    FlutterDesktopPluginRegistrarRef registrar) {
  offline_listening::OfflineListeningPlugin::RegisterWithRegistrar(
      flutter::PluginRegistrarManager::GetInstance()
          ->GetRegistrar<flutter::PluginRegistrarWindows>(registrar));
}
