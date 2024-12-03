#ifndef FLUTTER_PLUGIN_OFFLINE_LISTENING_PLUGIN_H_
#define FLUTTER_PLUGIN_OFFLINE_LISTENING_PLUGIN_H_

#include <flutter/method_channel.h>
#include <flutter/plugin_registrar_windows.h>

#include <memory>

namespace offline_listening {

class OfflineListeningPlugin : public flutter::Plugin {
 public:
  static void RegisterWithRegistrar(flutter::PluginRegistrarWindows *registrar);

  OfflineListeningPlugin();

  virtual ~OfflineListeningPlugin();

  // Disallow copy and assign.
  OfflineListeningPlugin(const OfflineListeningPlugin&) = delete;
  OfflineListeningPlugin& operator=(const OfflineListeningPlugin&) = delete;

  // Called when a method is called on this plugin's channel from Dart.
  void HandleMethodCall(
      const flutter::MethodCall<flutter::EncodableValue> &method_call,
      std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result);
};

}  // namespace offline_listening

#endif  // FLUTTER_PLUGIN_OFFLINE_LISTENING_PLUGIN_H_
