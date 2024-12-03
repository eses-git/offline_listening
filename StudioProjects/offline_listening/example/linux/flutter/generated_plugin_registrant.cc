//
//  Generated file. Do not edit.
//

// clang-format off

#include "generated_plugin_registrant.h"

#include <offline_listening/offline_listening_plugin.h>

void fl_register_plugins(FlPluginRegistry* registry) {
  g_autoptr(FlPluginRegistrar) offline_listening_registrar =
      fl_plugin_registry_get_registrar_for_plugin(registry, "OfflineListeningPlugin");
  offline_listening_plugin_register_with_registrar(offline_listening_registrar);
}
