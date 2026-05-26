package com.example.parfun;

public class AA {
}

class BB extends AA {

    // forbid override equals using final
    @Override
    public final boolean equals(Object obj) {
        return super.equals(obj);
    }

    @Override
    public final int hashCode() {
        return super.hashCode();
    }
}
