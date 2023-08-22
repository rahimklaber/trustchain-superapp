// Automatically generated by flapigen
package generated;


public final class ScriptContainer {

    public ScriptContainer() {
        mNativeObj = init();
    }
    private static native long init();

    public final void add_script(long satoshi_amount, byte [] script) {
        do_add_script(mNativeObj, satoshi_amount, script);
    }
    private static native void do_add_script(long self, long satoshi_amount, byte [] script);

    public synchronized void delete() {
        if (mNativeObj != 0) {
            do_delete(mNativeObj);
            mNativeObj = 0;
       }
    }
    @Override
    protected void finalize() throws Throwable {
        try {
            delete();
        }
        finally {
             super.finalize();
        }
    }
    private static native void do_delete(long me);
    /*package*/ ScriptContainer(InternalPointerMarker marker, long ptr) {
        assert marker == InternalPointerMarker.RAW_PTR;
        this.mNativeObj = ptr;
    }
    /*package*/ long mNativeObj;
}