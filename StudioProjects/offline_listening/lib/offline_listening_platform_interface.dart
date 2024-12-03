import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'offline_listening_method_channel.dart';

abstract class OfflineListeningPlatform extends PlatformInterface {
  /// Constructs a OfflineListeningPlatform.
  OfflineListeningPlatform() : super(token: _token);

  static final Object _token = Object();

  static OfflineListeningPlatform _instance = MethodChannelOfflineListening();

  /// The default instance of [OfflineListeningPlatform] to use.
  ///
  /// Defaults to [MethodChannelOfflineListening].
  static OfflineListeningPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [OfflineListeningPlatform] when
  /// they register themselves.
  static set instance(OfflineListeningPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> getPlatformVersion() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }
}
