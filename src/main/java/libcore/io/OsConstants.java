/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package libcore.io;

public final class OsConstants {
    private OsConstants() { }

    public static boolean S_ISBLK(int mode) { return (mode & S_IFMT) == S_IFBLK; }
    public static boolean S_ISCHR(int mode) { return (mode & S_IFMT) == S_IFCHR; }
    public static boolean S_ISDIR(int mode) { return (mode & S_IFMT) == S_IFDIR; }
    public static boolean S_ISFIFO(int mode) { return (mode & S_IFMT) == S_IFIFO; }
    public static boolean S_ISREG(int mode) { return (mode & S_IFMT) == S_IFREG; }
    public static boolean S_ISLNK(int mode) { return (mode & S_IFMT) == S_IFLNK; }
    public static boolean S_ISSOCK(int mode) { return (mode & S_IFMT) == S_IFSOCK; }

    public static int WEXITSTATUS(int status) { return (status & 0xff00) >> 8; }
    public static boolean WCOREDUMP(int status) { return (status & 0x80) != 0; }
    public static int WTERMSIG(int status) { return status & 0x7f; }
    public static int WSTOPSIG(int status) { return WEXITSTATUS(status); }
    public static boolean WIFEXITED(int status) { return (WTERMSIG(status) == 0); }
    public static boolean WIFSTOPPED(int status) { return (WTERMSIG(status) == 0x7f); }
    public static boolean WIFSIGNALED(int status) { return (WTERMSIG(status + 1) >= 2); }

    public static final int AF_INET = placeholder();
    public static final int AF_INET6 = placeholder();
    public static final int AF_UNIX = placeholder();
    public static final int AF_UNSPEC = placeholder();
    public static final int AI_ADDRCONFIG = placeholder();
    public static final int AI_ALL = placeholder();
    public static final int AI_CANONNAME = placeholder();
    public static final int AI_NUMERICHOST = placeholder();
    public static final int AI_NUMERICSERV = placeholder();
    public static final int AI_PASSIVE = placeholder();
    public static final int AI_V4MAPPED = placeholder();
    public static final int E2BIG = placeholder();
    public static final int EACCES = placeholder();
    public static final int EADDRINUSE = placeholder();
    public static final int EADDRNOTAVAIL = placeholder();
    public static final int EAFNOSUPPORT = placeholder();
    public static final int EAGAIN = placeholder();
    public static final int EAI_AGAIN = placeholder();
    public static final int EAI_BADFLAGS = placeholder();
    public static final int EAI_FAIL = placeholder();
    public static final int EAI_FAMILY = placeholder();
    public static final int EAI_MEMORY = placeholder();
    public static final int EAI_NODATA = placeholder();
    public static final int EAI_NONAME = placeholder();
    public static final int EAI_OVERFLOW = placeholder();
    public static final int EAI_SERVICE = placeholder();
    public static final int EAI_SOCKTYPE = placeholder();
    public static final int EAI_SYSTEM = placeholder();
    public static final int EALREADY = placeholder();
    public static final int EBADF = placeholder();
    public static final int EBADMSG = placeholder();
    public static final int EBUSY = placeholder();
    public static final int ECANCELED = placeholder();
    public static final int ECHILD = placeholder();
    public static final int ECONNABORTED = placeholder();
    public static final int ECONNREFUSED = placeholder();
    public static final int ECONNRESET = placeholder();
    public static final int EDEADLK = placeholder();
    public static final int EDESTADDRREQ = placeholder();
    public static final int EDOM = placeholder();
    public static final int EDQUOT = placeholder();
    public static final int EEXIST = placeholder();
    public static final int EFAULT = placeholder();
    public static final int EFBIG = placeholder();
    public static final int EHOSTUNREACH = placeholder();
    public static final int EIDRM = placeholder();
    public static final int EILSEQ = placeholder();
    public static final int EINPROGRESS = placeholder();
    public static final int EINTR = placeholder();
    public static final int EINVAL = placeholder();
    public static final int EIO = placeholder();
    public static final int EISCONN = placeholder();
    public static final int EISDIR = placeholder();
    public static final int ELOOP = placeholder();
    public static final int EMFILE = placeholder();
    public static final int EMLINK = placeholder();
    public static final int EMSGSIZE = placeholder();
    public static final int EMULTIHOP = placeholder();
    public static final int ENAMETOOLONG = placeholder();
    public static final int ENETDOWN = placeholder();
    public static final int ENETRESET = placeholder();
    public static final int ENETUNREACH = placeholder();
    public static final int ENFILE = placeholder();
    public static final int ENOBUFS = placeholder();
    public static final int ENODATA = placeholder();
    public static final int ENODEV = placeholder();
    public static final int ENOENT = placeholder();
    public static final int ENOEXEC = placeholder();
    public static final int ENOLCK = placeholder();
    public static final int ENOLINK = placeholder();
    public static final int ENOMEM = placeholder();
    public static final int ENOMSG = placeholder();
    public static final int ENOPROTOOPT = placeholder();
    public static final int ENOSPC = placeholder();
    public static final int ENOSR = placeholder();
    public static final int ENOSTR = placeholder();
    public static final int ENOSYS = placeholder();
    public static final int ENOTCONN = placeholder();
    public static final int ENOTDIR = placeholder();
    public static final int ENOTEMPTY = placeholder();
    public static final int ENOTSOCK = placeholder();
    public static final int ENOTSUP = placeholder();
    public static final int ENOTTY = placeholder();
    public static final int ENXIO = placeholder();
    public static final int EOPNOTSUPP = placeholder();
    public static final int EOVERFLOW = placeholder();
    public static final int EPERM = placeholder();
    public static final int EPIPE = placeholder();
    public static final int EPROTO = placeholder();
    public static final int EPROTONOSUPPORT = placeholder();
    public static final int EPROTOTYPE = placeholder();
    public static final int ERANGE = placeholder();
    public static final int EROFS = placeholder();
    public static final int ESPIPE = placeholder();
    public static final int ESRCH = placeholder();
    public static final int ESTALE = placeholder();
    public static final int ETIME = placeholder();
    public static final int ETIMEDOUT = placeholder();
    public static final int ETXTBSY = placeholder();
    public static final int EWOULDBLOCK = placeholder();
    public static final int EXDEV = placeholder();
    public static final int EXIT_FAILURE = placeholder();
    public static final int EXIT_SUCCESS = placeholder();
    public static final int FD_CLOEXEC = placeholder();
    public static final int FIONREAD = placeholder();
    public static final int F_DUPFD = placeholder();
    public static final int F_GETFD = placeholder();
    public static final int F_GETFL = placeholder();
    public static final int F_GETLK = placeholder();
    public static final int F_GETLK64 = placeholder();
    public static final int F_GETOWN = placeholder();
    public static final int F_OK = placeholder();
    public static final int F_RDLCK = placeholder();
    public static final int F_SETFD = placeholder();
    public static final int F_SETFL = placeholder();
    public static final int F_SETLK = placeholder();
    public static final int F_SETLK64 = placeholder();
    public static final int F_SETLKW = placeholder();
    public static final int F_SETLKW64 = placeholder();
    public static final int F_SETOWN = placeholder();
    public static final int F_UNLCK = placeholder();
    public static final int F_WRLCK = placeholder();
    public static final int IFF_ALLMULTI = placeholder();
    public static final int IFF_AUTOMEDIA = placeholder();
    public static final int IFF_BROADCAST = placeholder();
    public static final int IFF_DEBUG = placeholder();
    public static final int IFF_DYNAMIC = placeholder();
    public static final int IFF_LOOPBACK = placeholder();
    public static final int IFF_MASTER = placeholder();
    public static final int IFF_MULTICAST = placeholder();
    public static final int IFF_NOARP = placeholder();
    public static final int IFF_NOTRAILERS = placeholder();
    public static final int IFF_POINTOPOINT = placeholder();
    public static final int IFF_PORTSEL = placeholder();
    public static final int IFF_PROMISC = placeholder();
    public static final int IFF_RUNNING = placeholder();
    public static final int IFF_SLAVE = placeholder();
    public static final int IFF_UP = placeholder();
    public static final int IPPROTO_ICMP = placeholder();
    public static final int IPPROTO_IP = placeholder();
    public static final int IPPROTO_IPV6 = placeholder();
    public static final int IPPROTO_RAW = placeholder();
    public static final int IPPROTO_TCP = placeholder();
    public static final int IPPROTO_UDP = placeholder();
    public static final int IPV6_CHECKSUM = placeholder();
    public static final int IPV6_MULTICAST_HOPS = placeholder();
    public static final int IPV6_MULTICAST_IF = placeholder();
    public static final int IPV6_MULTICAST_LOOP = placeholder();
    public static final int IPV6_RECVDSTOPTS = placeholder();
    public static final int IPV6_RECVHOPLIMIT = placeholder();
    public static final int IPV6_RECVHOPOPTS = placeholder();
    public static final int IPV6_RECVPKTINFO = placeholder();
    public static final int IPV6_RECVRTHDR = placeholder();
    public static final int IPV6_RECVTCLASS = placeholder();
    public static final int IPV6_TCLASS = placeholder();
    public static final int IPV6_UNICAST_HOPS = placeholder();
    public static final int IPV6_V6ONLY = placeholder();
    public static final int IP_MULTICAST_IF = placeholder();
    public static final int IP_MULTICAST_LOOP = placeholder();
    public static final int IP_MULTICAST_TTL = placeholder();
    public static final int IP_TOS = placeholder();
    public static final int IP_TTL = placeholder();
    public static final int MAP_FIXED = placeholder();
    public static final int MAP_PRIVATE = placeholder();
    public static final int MAP_SHARED = placeholder();
    public static final int MCAST_JOIN_GROUP = placeholder();
    public static final int MCAST_LEAVE_GROUP = placeholder();
    public static final int MCL_CURRENT = placeholder();
    public static final int MCL_FUTURE = placeholder();
    public static final int MSG_CTRUNC = placeholder();
    public static final int MSG_DONTROUTE = placeholder();
    public static final int MSG_EOR = placeholder();
    public static final int MSG_OOB = placeholder();
    public static final int MSG_PEEK = placeholder();
    public static final int MSG_TRUNC = placeholder();
    public static final int MSG_WAITALL = placeholder();
    public static final int MS_ASYNC = placeholder();
    public static final int MS_INVALIDATE = placeholder();
    public static final int MS_SYNC = placeholder();
    public static final int NI_DGRAM = placeholder();
    public static final int NI_NAMEREQD = placeholder();
    public static final int NI_NOFQDN = placeholder();
    public static final int NI_NUMERICHOST = placeholder();
    public static final int NI_NUMERICSERV = placeholder();
    public static final int O_ACCMODE = placeholder();
    public static final int O_APPEND = placeholder();
    public static final int O_CREAT = placeholder();
    public static final int O_EXCL = placeholder();
    public static final int O_NOCTTY = placeholder();
    public static final int O_NONBLOCK = placeholder();
    public static final int O_RDONLY = placeholder();
    public static final int O_RDWR = placeholder();
    public static final int O_SYNC = placeholder();
    public static final int O_TRUNC = placeholder();
    public static final int O_WRONLY = placeholder();
    public static final int POLLERR = placeholder();
    public static final int POLLHUP = placeholder();
    public static final int POLLIN = placeholder();
    public static final int POLLNVAL = placeholder();
    public static final int POLLOUT = placeholder();
    public static final int POLLPRI = placeholder();
    public static final int POLLRDBAND = placeholder();
    public static final int POLLRDNORM = placeholder();
    public static final int POLLWRBAND = placeholder();
    public static final int POLLWRNORM = placeholder();
    public static final int PROT_EXEC = placeholder();
    public static final int PROT_NONE = placeholder();
    public static final int PROT_READ = placeholder();
    public static final int PROT_WRITE = placeholder();
    public static final int R_OK = placeholder();
    public static final int SEEK_CUR = placeholder();
    public static final int SEEK_END = placeholder();
    public static final int SEEK_SET = placeholder();
    public static final int SHUT_RD = placeholder();
    public static final int SHUT_RDWR = placeholder();
    public static final int SHUT_WR = placeholder();
    public static final int SIGABRT = placeholder();
    public static final int SIGALRM = placeholder();
    public static final int SIGBUS = placeholder();
    public static final int SIGCHLD = placeholder();
    public static final int SIGCONT = placeholder();
    public static final int SIGFPE = placeholder();
    public static final int SIGHUP = placeholder();
    public static final int SIGILL = placeholder();
    public static final int SIGINT = placeholder();
    public static final int SIGIO = placeholder();
    public static final int SIGKILL = placeholder();
    public static final int SIGPIPE = placeholder();
    public static final int SIGPROF = placeholder();
    public static final int SIGPWR = placeholder();
    public static final int SIGQUIT = placeholder();
    public static final int SIGRTMAX = placeholder();
    public static final int SIGRTMIN = placeholder();
    public static final int SIGSEGV = placeholder();
    public static final int SIGSTKFLT = placeholder();
    public static final int SIGSTOP = placeholder();
    public static final int SIGSYS = placeholder();
    public static final int SIGTERM = placeholder();
    public static final int SIGTRAP = placeholder();
    public static final int SIGTSTP = placeholder();
    public static final int SIGTTIN = placeholder();
    public static final int SIGTTOU = placeholder();
    public static final int SIGURG = placeholder();
    public static final int SIGUSR1 = placeholder();
    public static final int SIGUSR2 = placeholder();
    public static final int SIGVTALRM = placeholder();
    public static final int SIGWINCH = placeholder();
    public static final int SIGXCPU = placeholder();
    public static final int SIGXFSZ = placeholder();
    public static final int SIOCGIFADDR = placeholder();
    public static final int SIOCGIFBRDADDR = placeholder();
    public static final int SIOCGIFDSTADDR = placeholder();
    public static final int SIOCGIFNETMASK = placeholder();
    public static final int SOCK_DGRAM = placeholder();
    public static final int SOCK_RAW = placeholder();
    public static final int SOCK_SEQPACKET = placeholder();
    public static final int SOCK_STREAM = placeholder();
    public static final int SOL_SOCKET = placeholder();
    public static final int SO_BINDTODEVICE = placeholder();
    public static final int SO_BROADCAST = placeholder();
    public static final int SO_DEBUG = placeholder();
    public static final int SO_DONTROUTE = placeholder();
    public static final int SO_ERROR = placeholder();
    public static final int SO_KEEPALIVE = placeholder();
    public static final int SO_LINGER = placeholder();
    public static final int SO_OOBINLINE = placeholder();
    public static final int SO_RCVBUF = placeholder();
    public static final int SO_RCVLOWAT = placeholder();
    public static final int SO_RCVTIMEO = placeholder();
    public static final int SO_REUSEADDR = placeholder();
    public static final int SO_SNDBUF = placeholder();
    public static final int SO_SNDLOWAT = placeholder();
    public static final int SO_SNDTIMEO = placeholder();
    public static final int SO_TYPE = placeholder();
    public static final int STDERR_FILENO = placeholder();
    public static final int STDIN_FILENO = placeholder();
    public static final int STDOUT_FILENO = placeholder();
    public static final int S_IFBLK = placeholder();
    public static final int S_IFCHR = placeholder();
    public static final int S_IFDIR = placeholder();
    public static final int S_IFIFO = placeholder();
    public static final int S_IFLNK = placeholder();
    public static final int S_IFMT = placeholder();
    public static final int S_IFREG = placeholder();
    public static final int S_IFSOCK = placeholder();
    public static final int S_IRGRP = placeholder();
    public static final int S_IROTH = placeholder();
    public static final int S_IRUSR = placeholder();
    public static final int S_IRWXG = placeholder();
    public static final int S_IRWXO = placeholder();
    public static final int S_IRWXU = placeholder();
    public static final int S_ISGID = placeholder();
    public static final int S_ISUID = placeholder();
    public static final int S_ISVTX = placeholder();
    public static final int S_IWGRP = placeholder();
    public static final int S_IWOTH = placeholder();
    public static final int S_IWUSR = placeholder();
    public static final int S_IXGRP = placeholder();
    public static final int S_IXOTH = placeholder();
    public static final int S_IXUSR = placeholder();
    public static final int TCP_NODELAY = placeholder();
    public static final int WCONTINUED = placeholder();
    public static final int WEXITED = placeholder();
    public static final int WNOHANG = placeholder();
    public static final int WNOWAIT = placeholder();
    public static final int WSTOPPED = placeholder();
    public static final int WUNTRACED = placeholder();
    public static final int W_OK = placeholder();
    public static final int X_OK = placeholder();
    public static final int _SC_2_CHAR_TERM = placeholder();
    public static final int _SC_2_C_BIND = placeholder();
    public static final int _SC_2_C_DEV = placeholder();
    public static final int _SC_2_C_VERSION = placeholder();
    public static final int _SC_2_FORT_DEV = placeholder();
    public static final int _SC_2_FORT_RUN = placeholder();
    public static final int _SC_2_LOCALEDEF = placeholder();
    public static final int _SC_2_SW_DEV = placeholder();
    public static final int _SC_2_UPE = placeholder();
    public static final int _SC_2_VERSION = placeholder();
    public static final int _SC_AIO_LISTIO_MAX = placeholder();
    public static final int _SC_AIO_MAX = placeholder();
    public static final int _SC_AIO_PRIO_DELTA_MAX = placeholder();
    public static final int _SC_ARG_MAX = placeholder();
    public static final int _SC_ASYNCHRONOUS_IO = placeholder();
    public static final int _SC_ATEXIT_MAX = placeholder();
    public static final int _SC_AVPHYS_PAGES = placeholder();
    public static final int _SC_BC_BASE_MAX = placeholder();
    public static final int _SC_BC_DIM_MAX = placeholder();
    public static final int _SC_BC_SCALE_MAX = placeholder();
    public static final int _SC_BC_STRING_MAX = placeholder();
    public static final int _SC_CHILD_MAX = placeholder();
    public static final int _SC_CLK_TCK = placeholder();
    public static final int _SC_COLL_WEIGHTS_MAX = placeholder();
    public static final int _SC_DELAYTIMER_MAX = placeholder();
    public static final int _SC_EXPR_NEST_MAX = placeholder();
    public static final int _SC_FSYNC = placeholder();
    public static final int _SC_GETGR_R_SIZE_MAX = placeholder();
    public static final int _SC_GETPW_R_SIZE_MAX = placeholder();
    public static final int _SC_IOV_MAX = placeholder();
    public static final int _SC_JOB_CONTROL = placeholder();
    public static final int _SC_LINE_MAX = placeholder();
    public static final int _SC_LOGIN_NAME_MAX = placeholder();
    public static final int _SC_MAPPED_FILES = placeholder();
    public static final int _SC_MEMLOCK = placeholder();
    public static final int _SC_MEMLOCK_RANGE = placeholder();
    public static final int _SC_MEMORY_PROTECTION = placeholder();
    public static final int _SC_MESSAGE_PASSING = placeholder();
    public static final int _SC_MQ_OPEN_MAX = placeholder();
    public static final int _SC_MQ_PRIO_MAX = placeholder();
    public static final int _SC_NGROUPS_MAX = placeholder();
    public static final int _SC_NPROCESSORS_CONF = placeholder();
    public static final int _SC_NPROCESSORS_ONLN = placeholder();
    public static final int _SC_OPEN_MAX = placeholder();
    public static final int _SC_PAGESIZE = placeholder();
    public static final int _SC_PAGE_SIZE = placeholder();
    public static final int _SC_PASS_MAX = placeholder();
    public static final int _SC_PHYS_PAGES = placeholder();
    public static final int _SC_PRIORITIZED_IO = placeholder();
    public static final int _SC_PRIORITY_SCHEDULING = placeholder();
    public static final int _SC_REALTIME_SIGNALS = placeholder();
    public static final int _SC_RE_DUP_MAX = placeholder();
    public static final int _SC_RTSIG_MAX = placeholder();
    public static final int _SC_SAVED_IDS = placeholder();
    public static final int _SC_SEMAPHORES = placeholder();
    public static final int _SC_SEM_NSEMS_MAX = placeholder();
    public static final int _SC_SEM_VALUE_MAX = placeholder();
    public static final int _SC_SHARED_MEMORY_OBJECTS = placeholder();
    public static final int _SC_SIGQUEUE_MAX = placeholder();
    public static final int _SC_STREAM_MAX = placeholder();
    public static final int _SC_SYNCHRONIZED_IO = placeholder();
    public static final int _SC_THREADS = placeholder();
    public static final int _SC_THREAD_ATTR_STACKADDR = placeholder();
    public static final int _SC_THREAD_ATTR_STACKSIZE = placeholder();
    public static final int _SC_THREAD_DESTRUCTOR_ITERATIONS = placeholder();
    public static final int _SC_THREAD_KEYS_MAX = placeholder();
    public static final int _SC_THREAD_PRIORITY_SCHEDULING = placeholder();
    public static final int _SC_THREAD_PRIO_INHERIT = placeholder();
    public static final int _SC_THREAD_PRIO_PROTECT = placeholder();
    public static final int _SC_THREAD_SAFE_FUNCTIONS = placeholder();
    public static final int _SC_THREAD_STACK_MIN = placeholder();
    public static final int _SC_THREAD_THREADS_MAX = placeholder();
    public static final int _SC_TIMERS = placeholder();
    public static final int _SC_TIMER_MAX = placeholder();
    public static final int _SC_TTY_NAME_MAX = placeholder();
    public static final int _SC_TZNAME_MAX = placeholder();
    public static final int _SC_VERSION = placeholder();
    public static final int _SC_XBS5_ILP32_OFF32 = placeholder();
    public static final int _SC_XBS5_ILP32_OFFBIG = placeholder();
    public static final int _SC_XBS5_LP64_OFF64 = placeholder();
    public static final int _SC_XBS5_LPBIG_OFFBIG = placeholder();
    public static final int _SC_XOPEN_CRYPT = placeholder();
    public static final int _SC_XOPEN_ENH_I18N = placeholder();
    public static final int _SC_XOPEN_LEGACY = placeholder();
    public static final int _SC_XOPEN_REALTIME = placeholder();
    public static final int _SC_XOPEN_REALTIME_THREADS = placeholder();
    public static final int _SC_XOPEN_SHM = placeholder();
    public static final int _SC_XOPEN_UNIX = placeholder();
    public static final int _SC_XOPEN_VERSION = placeholder();
    public static final int _SC_XOPEN_XCU_VERSION = placeholder();

    public static String gaiName(int error) {
        if (error == EAI_AGAIN) {
            return "EAI_AGAIN";
        }
        if (error == EAI_BADFLAGS) {
            return "EAI_BADFLAGS";
        }
        if (error == EAI_FAIL) {
            return "EAI_FAIL";
        }
        if (error == EAI_FAMILY) {
            return "EAI_FAMILY";
        }
        if (error == EAI_MEMORY) {
            return "EAI_MEMORY";
        }
        if (error == EAI_NODATA) {
            return "EAI_NODATA";
        }
        if (error == EAI_NONAME) {
            return "EAI_NONAME";
        }
        if (error == EAI_OVERFLOW) {
            return "EAI_OVERFLOW";
        }
        if (error == EAI_SERVICE) {
            return "EAI_SERVICE";
        }
        if (error == EAI_SOCKTYPE) {
            return "EAI_SOCKTYPE";
        }
        if (error == EAI_SYSTEM) {
            return "EAI_SYSTEM";
        }
        return null;
    }

    public static String errnoName(int errno) {
        if (errno == E2BIG) {
            return "E2BIG";
        }
        if (errno == EACCES) {
            return "EACCES";
        }
        if (errno == EADDRINUSE) {
            return "EADDRINUSE";
        }
        if (errno == EADDRNOTAVAIL) {
            return "EADDRNOTAVAIL";
        }
        if (errno == EAFNOSUPPORT) {
            return "EAFNOSUPPORT";
        }
        if (errno == EAGAIN) {
            return "EAGAIN";
        }
        if (errno == EALREADY) {
            return "EALREADY";
        }
        if (errno == EBADF) {
            return "EBADF";
        }
        if (errno == EBADMSG) {
            return "EBADMSG";
        }
        if (errno == EBUSY) {
            return "EBUSY";
        }
        if (errno == ECANCELED) {
            return "ECANCELED";
        }
        if (errno == ECHILD) {
            return "ECHILD";
        }
        if (errno == ECONNABORTED) {
            return "ECONNABORTED";
        }
        if (errno == ECONNREFUSED) {
            return "ECONNREFUSED";
        }
        if (errno == ECONNRESET) {
            return "ECONNRESET";
        }
        if (errno == EDEADLK) {
            return "EDEADLK";
        }
        if (errno == EDESTADDRREQ) {
            return "EDESTADDRREQ";
        }
        if (errno == EDOM) {
            return "EDOM";
        }
        if (errno == EDQUOT) {
            return "EDQUOT";
        }
        if (errno == EEXIST) {
            return "EEXIST";
        }
        if (errno == EFAULT) {
            return "EFAULT";
        }
        if (errno == EFBIG) {
            return "EFBIG";
        }
        if (errno == EHOSTUNREACH) {
            return "EHOSTUNREACH";
        }
        if (errno == EIDRM) {
            return "EIDRM";
        }
        if (errno == EILSEQ) {
            return "EILSEQ";
        }
        if (errno == EINPROGRESS) {
            return "EINPROGRESS";
        }
        if (errno == EINTR) {
            return "EINTR";
        }
        if (errno == EINVAL) {
            return "EINVAL";
        }
        if (errno == EIO) {
            return "EIO";
        }
        if (errno == EISCONN) {
            return "EISCONN";
        }
        if (errno == EISDIR) {
            return "EISDIR";
        }
        if (errno == ELOOP) {
            return "ELOOP";
        }
        if (errno == EMFILE) {
            return "EMFILE";
        }
        if (errno == EMLINK) {
            return "EMLINK";
        }
        if (errno == EMSGSIZE) {
            return "EMSGSIZE";
        }
        if (errno == EMULTIHOP) {
            return "EMULTIHOP";
        }
        if (errno == ENAMETOOLONG) {
            return "ENAMETOOLONG";
        }
        if (errno == ENETDOWN) {
            return "ENETDOWN";
        }
        if (errno == ENETRESET) {
            return "ENETRESET";
        }
        if (errno == ENETUNREACH) {
            return "ENETUNREACH";
        }
        if (errno == ENFILE) {
            return "ENFILE";
        }
        if (errno == ENOBUFS) {
            return "ENOBUFS";
        }
        if (errno == ENODATA) {
            return "ENODATA";
        }
        if (errno == ENODEV) {
            return "ENODEV";
        }
        if (errno == ENOENT) {
            return "ENOENT";
        }
        if (errno == ENOEXEC) {
            return "ENOEXEC";
        }
        if (errno == ENOLCK) {
            return "ENOLCK";
        }
        if (errno == ENOLINK) {
            return "ENOLINK";
        }
        if (errno == ENOMEM) {
            return "ENOMEM";
        }
        if (errno == ENOMSG) {
            return "ENOMSG";
        }
        if (errno == ENOPROTOOPT) {
            return "ENOPROTOOPT";
        }
        if (errno == ENOSPC) {
            return "ENOSPC";
        }
        if (errno == ENOSR) {
            return "ENOSR";
        }
        if (errno == ENOSTR) {
            return "ENOSTR";
        }
        if (errno == ENOSYS) {
            return "ENOSYS";
        }
        if (errno == ENOTCONN) {
            return "ENOTCONN";
        }
        if (errno == ENOTDIR) {
            return "ENOTDIR";
        }
        if (errno == ENOTEMPTY) {
            return "ENOTEMPTY";
        }
        if (errno == ENOTSOCK) {
            return "ENOTSOCK";
        }
        if (errno == ENOTSUP) {
            return "ENOTSUP";
        }
        if (errno == ENOTTY) {
            return "ENOTTY";
        }
        if (errno == ENXIO) {
            return "ENXIO";
        }
        if (errno == EOPNOTSUPP) {
            return "EOPNOTSUPP";
        }
        if (errno == EOVERFLOW) {
            return "EOVERFLOW";
        }
        if (errno == EPERM) {
            return "EPERM";
        }
        if (errno == EPIPE) {
            return "EPIPE";
        }
        if (errno == EPROTO) {
            return "EPROTO";
        }
        if (errno == EPROTONOSUPPORT) {
            return "EPROTONOSUPPORT";
        }
        if (errno == EPROTOTYPE) {
            return "EPROTOTYPE";
        }
        if (errno == ERANGE) {
            return "ERANGE";
        }
        if (errno == EROFS) {
            return "EROFS";
        }
        if (errno == ESPIPE) {
            return "ESPIPE";
        }
        if (errno == ESRCH) {
            return "ESRCH";
        }
        if (errno == ESTALE) {
            return "ESTALE";
        }
        if (errno == ETIME) {
            return "ETIME";
        }
        if (errno == ETIMEDOUT) {
            return "ETIMEDOUT";
        }
        if (errno == ETXTBSY) {
            return "ETXTBSY";
        }
        if (errno == EWOULDBLOCK) {
            return "EWOULDBLOCK";
        }
        if (errno == EXDEV) {
            return "EXDEV";
        }
        return null;
    }

    private static native void initConstants();

    // A hack to avoid these constants being inlined by javac...
    private static int placeholder() { return 0; }
    // ...because we want to initialize them at runtime.
    static {
        initConstants();
    }
}
