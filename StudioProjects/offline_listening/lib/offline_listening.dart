import 'package:flutter/services.dart';

class OfflineListening {
  static const MethodChannel _channel = MethodChannel('flutter_mfcc_plugin');

  /// Checks if the TensorFlow Lite model is loaded correctly.
  /// Returns `true` if the model is successfully loaded, `false` otherwise.
  static Future<bool> isModelLoaded(String modelPath) async {
    try {
      final bool result = await _channel.invokeMethod('isModelLoaded', {'modelPath': modelPath});
      return result;
    } catch (e) {
      print('Error checking model load status: $e');
      return false;
    }
  }

  /// Starts listening for wake words using TensorFlow Lite models.
  ///
  /// Throws an exception if the listening process cannot be started.
  static Future<void> startListening({required List<String> modelPaths}) async {
    try {
      if (modelPaths.isEmpty) {
        throw ArgumentError('Model paths cannot be empty.');
      }

      await _channel.invokeMethod('startListening', {'modelPaths': modelPaths});
      print('Listening started successfully.');
    } catch (e) {
      print('Error starting listening: $e');
      rethrow;
    }
  }

  /// Stops the listening process.
  ///
  /// Throws an exception if the listening process cannot be stopped.
  static Future<void> stopListening() async {
    try {
      await _channel.invokeMethod('stopListening');
      print('Listening stopped successfully.');
    } catch (e) {
      print('Error stopping listening: $e');
      rethrow;
    }
  }
}
