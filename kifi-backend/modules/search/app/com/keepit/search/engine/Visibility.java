package com.keepit.search.engine;

public final class Visibility {
  // NOTE: this is stored as the record type in DataBuffer. Only 7 bits are available for record type.
  public static final int RESTRICTED = 0x00;

  public static final int OTHERS = 0x01;
  public static final int NETWORK = 0x02;
  public static final int MEMBER = 0x04;
  public static final int OWNER = 0x08;
  //  unused = 0x10
  public static final int HAS_SECONDARY_ID = 0x20;
  public static final int LIB_NAME_MATCH = 0x40;

  public static String name(int visibility) {
    if ((visibility & Visibility.OWNER) != 0) return "owner";
    if ((visibility & Visibility.MEMBER) != 0) return "member";
    if ((visibility & Visibility.NETWORK) != 0) return "network";
    if ((visibility & Visibility.OTHERS) != 0) return "others";
    return "restricted";
  }

  public static String toString(int visibility) {
    visibility &= (OWNER | MEMBER | NETWORK | OTHERS);

    if (visibility == 0) {
      return name(0);
    } else {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; visibility != 0; i++) {
        if ((visibility & 1) == 1) {
          sb.append(name(1 << i));
          visibility >>= 1;
          if (visibility != 0) sb.append(',');
        } else {
          visibility >>= 1;
        }
      }
      return sb.toString();
    }
  }
}
