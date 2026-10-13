package server;

/** Agrupa los componentes del servidor para inyectarlos en los servants. */
final class ServerContext {

    final UserRegistry users;
    final RoomRegistry rooms;
    final FileRelay files;
    final CallManager calls;

    ServerContext(UserRegistry users, RoomRegistry rooms, FileRelay files, CallManager calls) {
        this.users = users;
        this.rooms = rooms;
        this.files = files;
        this.calls = calls;
    }
}
