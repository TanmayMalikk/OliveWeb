'use strict'

async function connectMumbleIce({
  Ice,
  MumbleServer,
  host = '127.0.0.1',
  port = 6502,
  serverId = 1
}) {
  const communicator = Ice.initialize([]);

  try {
    const proxy = communicator.stringToProxy(
        `Meta:tcp -h ${host} -p ${port}`
    );
    const meta = await MumbleServer.MetaPrx.checkedCast(proxy);
    if (!meta) {
      throw new Error ('Could not Connect to the Mumble Meta interface');
    }
    const mumbleServer = await meta.getServer(serverId);
    if (!mumbleServer){
      throw new Error (`Mumble server ${serverId} was not found`);
    }

    return {
      mumbleServer,
      close: () => communicator.destroy()
    };
  } catch (error) {
    await communicator.destroy();
    throw error;
  }
}

module.exports = { connectMumbleIce };