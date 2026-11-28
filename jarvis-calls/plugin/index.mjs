import { definePluginEntry } from 'openclaw/plugin-sdk/plugin-entry';
import { createCallTool } from './call-tool.mjs';

export default definePluginEntry({
  id: 'jarvis-calls', name: 'Jarvis Calls', description: 'Private calls to the owner’s paired phone.',
  register(api) { api.registerTool(context => createCallTool(api, context), { optional: true }); },
});
