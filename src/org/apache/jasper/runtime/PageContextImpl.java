package org.apache.jasper.runtime;

import java.io.IOException;
import java.io.Writer;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Set;

import javax.el.ELContext;
import javax.el.ELException;
import javax.el.ExpressionFactory;
import javax.el.ImportHandler;
import javax.el.ValueExpression;
import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.jsp.JspException;
import javax.servlet.jsp.JspFactory;
import javax.servlet.jsp.JspWriter;
import javax.servlet.jsp.PageContext;
import javax.servlet.jsp.tagext.BodyContent;

import org.apache.jasper.Constants;
import org.apache.jasper.compiler.Localizer;
import org.apache.jasper.el.ELContextImpl;
import org.apache.jasper.runtime.JspContextWrapper.ELContextWrapper;
import org.apache.jasper.security.SecurityUtil;

/**
 * 从JSP规范的PageContext 类的实现. 也可作为EL的 VariableResolver.
 */
public class PageContextImpl extends PageContext {

    private static final JspFactory jspf = JspFactory.getDefaultFactory();

    private BodyContentImpl[] outs;

    private int depth;

    // per-servlet state
    private Servlet servlet;

    private ServletConfig config;

    private ServletContext context;

    private JspApplicationContextImpl applicationContext;

    private String errorPageURL;

    // page-scope attributes
    private final transient HashMap<String, Object> attributes;

    // per-request state
    private transient ServletRequest request;

    private transient ServletResponse response;

    private transient HttpSession session;

    private transient ELContextImpl elContext;

    private boolean isIncluded;


    // 初始输出流
    private transient JspWriter out;

    private transient JspWriterImpl baseOut;

    PageContextImpl() {
        this.outs = new BodyContentImpl[0];
        this.attributes = new HashMap<>(16);
        this.depth = -1;
    }

    @Override
    public void initialize(Servlet servlet, ServletRequest request,
            ServletResponse response, String errorPageURL,
            boolean needsSession, int bufferSize, boolean autoFlush)
            throws IOException {

        // initialize state
        this.servlet = servlet;
        this.config = servlet.getServletConfig();
        this.context = config.getServletContext();
        this.errorPageURL = errorPageURL;
        this.request = request;
        this.response = response;

        // initialize application context
        this.applicationContext = JspApplicationContextImpl.getInstance(context);

        // Setup session (if required)
        if (request instanceof HttpServletRequest && needsSession)
            this.session = ((HttpServletRequest) request).getSession();
        if (needsSession && session == null)
            throw new IllegalStateException(
                    "Page needs a session and none is available");

        // initialize the initial out ...
        depth = -1;
        if (bufferSize == JspWriter.DEFAULT_BUFFER) {
            bufferSize = Constants.DEFAULT_BUFFER_SIZE;
        }
        if (this.baseOut == null) {
            this.baseOut = new JspWriterImpl(response, bufferSize, autoFlush);
        } else {
            this.baseOut.init(response, bufferSize, autoFlush);
        }
        this.out = baseOut;

        // register names/values as per spec
        setAttribute(OUT, this.out);
        setAttribute(REQUEST, request);
        setAttribute(RESPONSE, response);

        if (session != null)
            setAttribute(SESSION, session);

        setAttribute(PAGE, servlet);
        setAttribute(CONFIG, config);
        setAttribute(PAGECONTEXT, this);
        setAttribute(APPLICATION, context);

        isIncluded = request.getAttribute(
                RequestDispatcher.INCLUDE_SERVLET_PATH) != null;
    }

    @Override
    public void release() {
        out = baseOut;
        try {
            if (isIncluded) {
                ((JspWriterImpl) out).flushBuffer();
                // push it into the including jspWriter
            } else {
                // Old code:
                // out.flush();
                // 即使没有包括在内, 也不要刷新缓冲区(即我们是主页. servlet 将刷新它并关闭流.
                ((JspWriterImpl) out).flushBuffer();
            }
        } catch (IOException ex) {
            IllegalStateException ise = new IllegalStateException(Localizer.getMessage("jsp.error.flush"), ex);
            throw ise;
        } finally {
            servlet = null;
            config = null;
            context = null;
            applicationContext = null;
            elContext = null;
            errorPageURL = null;
            request = null;
            response = null;
            depth = -1;
            baseOut.recycle();
            session = null;
            attributes.clear();
            for (BodyContentImpl body: outs) {
                body.recycle();
            }
        }
    }

    @Override
    public Object getAttribute(final String name) {

        if (name == null) {
            throw new NullPointerException(Localizer
                    .getMessage("jsp.error.attribute.null_name"));
        }

        if (SecurityUtil.isPackageProtectionEnabled()) {
            return AccessController.doPrivileged(
                    new PrivilegedAction<Object>() {
                @Override
                public Object run() {
                    return doGetAttribute(name);
                }
            });
        } else {
            return doGetAttribute(name);
        }

    }

    private Object doGetAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public Object getAttribute(final String name, final int scope) {

        if (name == null) {
            throw new NullPointerException(Localizer
                    .getMessage("jsp.error.attribute.null_name"));
        }

        if (SecurityUtil.isPackageProtectionEnabled()) {
            return AccessController.doPrivileged(
                    new PrivilegedAction<Object>() {
                @Override
                public Object run() {
                    return doGetAttribute(name, scope);
                }
            });
        } else {
            return doGetAttribute(name, scope);
        }

    }

    private Object doGetAttribute(String name, int scope) {
        switch (scope) {
        case PAGE_SCOPE:
            return attributes.get(name);

        case REQUEST_SCOPE:
            return request.getAttribute(name);

        case SESSION_SCOPE:
            if (session == null) {
                throw new IllegalStateException(Localizer
                        .getMessage("jsp.error.page.noSession"));
            }
            return session.getAttribute(name);

        case APPLICATION_SCOPE:
            return context.getAttribute(name);

        default:
            throw new IllegalArgumentException("Invalid scope");
        }
    }

    @Override
    public void setAttribute(final String name, final Object attribute) {

        if (name == null) {
            throw new NullPointerException(Localizer
                    .getMessage("jsp.error.attribute.null_name"));
        }

        if (SecurityUtil.isPackageProtectionEnabled()) {
            AccessController.doPrivileged(new PrivilegedAction<Void>() {
                @Override
                public Void run() {
                    doSetAttribute(name, attribute);
                    return null;
                }
            });
        } else {
            doSetAttribute(name, attribute);
        }
    }

    private void doSetAttribute(String name, Object attribute) {
        if (attribute != null) {
            attributes.put(name, attribute);
        } else {
            removeAttribute(name, PAGE_SCOPE);
        }
    }

    @Override
    public void setAttribute(final String name, final Object o, final int scope) {

        if (name == null) {
            throw new NullPointerException(Localizer
                    .getMessage("jsp.error.attribute.null_name"));
        }

        if (SecurityUtil.isPackageProtectionEnabled()) {
            AccessController.doPrivileged(new PrivilegedAction<Void>() {
                @Override
                public Void run() {
                    doSetAttribute(name, o, scope);
                    return null;
                }
            });
        } else {
            doSetAttribute(name, o, scope);
        }

    }

    private void doSetAttribute(String name, Object o, int scope) {
        if (o != null) {
            switch (scope) {
            case PAGE_SCOPE:
                attributes.put(name, o);
                break;

            case REQUEST_SCOPE:
                request.setAttribute(name, o);
                break;

            case SESSION_SCOPE:
                if (session == null) {
                    throw new IllegalStateException(Localizer
                            .getMessage("jsp.error.page.noSession"));
                }
                session.setAttribute(name, o);
                break;

            case APPLICATION_SCOPE:
                context.setAttribute(name, o);
                break;

            default:
                throw new IllegalArgumentException("Invalid scope");
            }
        } else {
            removeAttribute(name, scope);
        }
    }

    @Override
    public void removeAttribute(final String name, final int scope) {

        if (name == null) {
            throw new NullPointerException(Localizer
                    .getMessage("jsp.error.attribute.null_name"));
        }
        if (SecurityUtil.isPackageProtectionEnabled()) {
            AccessController.doPrivileged(new PrivilegedAction<Void>() {
                @Override
                public Void run() {
                    doRemoveAttribute(name, scope);
                    return null;
                }
            });
        } else {
            doRemoveAttribute(name, scope);
        }
    }

    private void doRemoveAttribute(String name, int scope) {
        switch (scope) {
        case PAGE_SCOPE:
            attributes.remove(name);
            break;

        case REQUEST_SCOPE:
            request.removeAttribute(name);
            break;

        case SESSION_SCOPE:
            if (session == null) {
                throw new IllegalStateException(Localizer
                        .getMessage("jsp.error.page.noSession"));
            }
            session.removeAttribute(name);
            break;

        case APPLICATION_SCOPE:
            context.removeAttribute(name);
            break;

        default:
            throw new IllegalArgumentException("Invalid scope");
        }
    }

    @Override
    public int getAttributesScope(final String name) {

        if (name == null) {
            throw new NullPointerException(Localizer
                    .getMessage("jsp.error.attribute.null_name"));
        }

        if (SecurityUtil.isPackageProtectionEnabled()) {
            return (AccessController
                    .doPrivileged(new PrivilegedAction<Integer>() {
                        @Override
                        public Integer run() {
                            return Integer.valueOf(doGetAttributeScope(name));
                        }
                    })).intValue();
        } else {
            return doGetAttributeScope(name);
        }
    }

    private int doGetAttributeScope(String name) {
        if (attributes.get(name) != null)
            return PAGE_SCOPE;

        if (request.getAttribute(name) != null)
            return REQUEST_SCOPE;

        if (session != null) {
            try {
                if (session.getAttribute(name) != null)
                    return SESSION_SCOPE;
            } catch(IllegalStateException ise) {
                // Session has been invalidated.
                // Ignore and fall through to application scope.
            }
        }

        if (context.getAttribute(name) != null)
            return APPLICATION_SCOPE;

        return 0;
    }

    @Override
    public Object findAttribute(final String name) {
        if (SecurityUtil.isPackageProtectionEnabled()) {
            return AccessController.doPrivileged(
                    new PrivilegedAction<Object>() {
                @Override
                public Object run() {
                    if (name == null) {
                        throw new NullPointerException(Localizer
                                .getMessage("jsp.error.attribute.null_name"));
                    }

                    return doFindAttribute(name);
                }
            });
        } else {
            if (name == null) {
                throw new NullPointerException(Localizer
                        .getMessage("jsp.error.attribute.null_name"));
            }

            return doFindAttribute(name);
        }
    }

    private Object doFindAttribute(String name) {

        Object o = attributes.get(name);
        if (o != null)
            return o;

        o = request.getAttribute(name);
        if (o != null)
            return o;

        if (session != null) {
            try {
                o = session.getAttribute(name);
            } catch(IllegalStateException ise) {
                // Session has been invalidated.
                // Ignore and fall through to application scope.
            }
            if (o != null)
                return o;
        }

        return context.getAttribute(name);
    }

    @Override
    public Enumeration<String> getAttributeNamesInScope(final int scope) {
        if (SecurityUtil.isPackageProtectionEnabled()) {
            return AccessController.doPrivileged(
                    new PrivilegedAction<Enumeration<String>>() {
                        @Override
                        public Enumeration<String> run() {
                            return doGetAttributeNamesInScope(scope);
                        }
                    });
        } else {
            return doGetAttributeNamesInScope(scope);
        }
    }

    private Enumeration<String> doGetAttributeNamesInScope(int scope) {
        switch (scope) {
        case PAGE_SCOPE:
            return Collections.enumeration(attributes.keySet());

        case REQUEST_SCOPE:
            return request.getAttributeNames();

        case SESSION_SCOPE:
            if (session == null) {
                throw new IllegalStateException(Localizer
                        .getMessage("jsp.error.page.noSession"));
            }
            return session.getAttributeNames();

        case APPLICATION_SCOPE:
            return context.getAttributeNames();

        default:
            throw new IllegalArgumentException("Invalid scope");
        }
    }

    @Override
    public void removeAttribute(final String name) {

        if (name == null) {
            throw new NullPointerException(Localizer
                    .getMessage("jsp.error.attribute.null_name"));
        }

        if (SecurityUtil.isPackageProtectionEnabled()) {
            AccessController.doPrivileged(new PrivilegedAction<Void>() {
                @Override
                public Void run() {
                    doRemoveAttribute(name);
                    return null;
                }
            });
        } else {
            doRemoveAttribute(name);
        }
    }

    private void doRemoveAttribute(String name) {
        removeAttribute(name, PAGE_SCOPE);
        removeAttribute(name, REQUEST_SCOPE);
        if( session != null ) {
            try {
                removeAttribute(name, SESSION_SCOPE);
            } catch(IllegalStateException ise) {
                // Session has been invalidated.
                // Ignore and fall throw to application scope.
            }
        }
        removeAttribute(name, APPLICATION_SCOPE);
    }

    @Override
    public JspWriter getOut() {
        return out;
    }

    @Override
    public HttpSession getSession() {
        return session;
    }

    @Override
    public ServletConfig getServletConfig() {
        return config;
    }

    @Override
    public ServletContext getServletContext() {
        return config.getServletContext();
    }

    @Override
    public ServletRequest getRequest() {
        return request;
    }

    @Override
    public ServletResponse getResponse() {
        return response;
    }

    /**
     * 返回与此页上下文关联的异常.
     * <p/>
     * 添加包装的 Throwables 以避免 ClassCastException: see Bugzilla 31171 for details.
     *
     * @return 与此页上下文关联的异常.
     */
    @Override
    public Exception getException() {
        Throwable t = JspRuntimeLibrary.getThrowable(request);

        // Only wrap if needed
        if ((t != null) && (!(t instanceof Exception))) {
            t = new JspException(t);
        }

        return (Exception) t;
    }

    @Override
    public Object getPage() {
        return servlet;
    }

    private final String getAbsolutePathRelativeToContext(String relativeUrlPath) {
        String path = relativeUrlPath;

        if (!path.startsWith("/")) {
            String uri = (String) request.getAttribute(
                    RequestDispatcher.INCLUDE_SERVLET_PATH);
            if (uri == null)
                uri = ((HttpServletRequest) request).getServletPath();
            String baseURI = uri.substring(0, uri.lastIndexOf('/'));
            path = baseURI + '/' + path;
        }

        return path;
    }

    @Override
    public void include(String relativeUrlPath) throws ServletException,
            IOException {
        JspRuntimeLibrary
                .include(request, response, relativeUrlPath, out, true);
    }

    @Override
    public void include(final String relativeUrlPath, final boolean flush)
            throws ServletException, IOException {
        if (SecurityUtil.isPackageProtectionEnabled()) {
            try {
                AccessController.doPrivileged(
                        new PrivilegedExceptionAction<Void>() {
                    @Override
                    public Void run() throws Exception {
                        doInclude(relativeUrlPath, flush);
                        return null;
                    }
                });
            } catch (PrivilegedActionException e) {
                Exception ex = e.getException();
                if (ex instanceof IOException) {
                    throw (IOException) ex;
                } else {
                    throw (ServletException) ex;
                }
            }
        } else {
            doInclude(relativeUrlPath, flush);
        }
    }

    private void doInclude(String relativeUrlPath, boolean flush)
            throws ServletException, IOException {
        JspRuntimeLibrary.include(request, response, relativeUrlPath, out,
                flush);
    }

    @Override
    public void forward(final String relativeUrlPath) throws ServletException,
            IOException {
        if (SecurityUtil.isPackageProtectionEnabled()) {
            try {
                AccessController.doPrivileged(
                        new PrivilegedExceptionAction<Void>() {
                    @Override
                    public Void run() throws Exception {
                        doForward(relativeUrlPath);
                        return null;
                    }
                });
            } catch (PrivilegedActionException e) {
                Exception ex = e.getException();
                if (ex instanceof IOException) {
                    throw (IOException) ex;
                } else {
                    throw (ServletException) ex;
                }
            }
        } else {
            doForward(relativeUrlPath);
        }
    }

    private void doForward(String relativeUrlPath) throws ServletException,
            IOException {

        // JSP.4.5 如果缓冲区被刷新, 抛出 IllegalStateException
        try {
            out.clear();
            baseOut.clear();
        } catch (IOException ex) {
            IllegalStateException ise = new IllegalStateException(Localizer
                    .getMessage("jsp.error.attempt_to_clear_flushed_buffer"));
            ise.initCause(ex);
            throw ise;
        }

        // 请确保响应对象不是包含的包装器
        while (response instanceof ServletResponseWrapperInclude) {
            response = ((ServletResponseWrapperInclude) response).getResponse();
        }

        final String path = getAbsolutePathRelativeToContext(relativeUrlPath);
        String includeUri = (String) request.getAttribute(
                RequestDispatcher.INCLUDE_SERVLET_PATH);

        if (includeUri != null)
            request.removeAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH);
        try {
            context.getRequestDispatcher(path).forward(request, response);
        } finally {
            if (includeUri != null)
                request.setAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH,
                        includeUri);
        }
    }

    @Override
    public BodyContent pushBody() {
        return (BodyContent) pushBody(null);
    }

    @Override
    public JspWriter pushBody(Writer writer) {
        depth++;
        if (depth >= outs.length) {
            BodyContentImpl[] newOuts = new BodyContentImpl[depth + 1];
            for (int i = 0; i < outs.length; i++) {
                newOuts[i] = outs[i];
            }
            newOuts[depth] = new BodyContentImpl(out);
            outs = newOuts;
        }

        outs[depth].setWriter(writer);
        out = outs[depth];

        // 更新页面范围属性命名空间中的 "out"属性的值
        setAttribute(OUT, out);

        return outs[depth];
    }

    @Override
    public JspWriter popBody() {
        depth--;
        if (depth >= 0) {
            out = outs[depth];
        } else {
            out = baseOut;
        }

        // 更新页面范围属性命名空间中的 "out"属性的值
        setAttribute(OUT, out);

        return out;
    }

    @Override
    public void handlePageException(Exception ex) throws IOException,
            ServletException {
        // 绝对不能被调用, 因为在生成的servlet调用handleException() 抛出Throwable.
        handlePageException((Throwable) ex);
    }

    @Override
    public void handlePageException(final Throwable t) throws IOException,
            ServletException {
        if (t == null)
            throw new NullPointerException("null Throwable");

        if (SecurityUtil.isPackageProtectionEnabled()) {
            try {
                AccessController.doPrivileged(
                        new PrivilegedExceptionAction<Void>() {
                    @Override
                    public Void run() throws Exception {
                        doHandlePageException(t);
                        return null;
                    }
                });
            } catch (PrivilegedActionException e) {
                Exception ex = e.getException();
                if (ex instanceof IOException) {
                    throw (IOException) ex;
                } else {
                    throw (ServletException) ex;
                }
            }
        } else {
            doHandlePageException(t);
        }

    }

    @SuppressWarnings("deprecation") // Still jave to support old JSP EL
    private void doHandlePageException(Throwable t) throws IOException,
            ServletException {

        if (errorPageURL != null && !errorPageURL.equals("")) {

            /*
             * 设置请求属性.
             * 不要在这里设置 javax.servlet.error.exception 属性
             * (相反, 在生成的servlet设置错误页面) 为了防止 ErrorReportValve, 作为请求向错误页面发送请求的一部分调用它, 从抛出它,
             * 如果尚未提交响应(如果错误页面是JSP页面，响应将被提交).
             */
            request.setAttribute(PageContext.EXCEPTION, t);
            request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE,
                    Integer.valueOf(HttpServletResponse.SC_INTERNAL_SERVER_ERROR));
            request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI,
                    ((HttpServletRequest) request).getRequestURI());
            request.setAttribute(RequestDispatcher.ERROR_SERVLET_NAME,
                    config.getServletName());
            try {
                forward(errorPageURL);
            } catch (IllegalStateException ise) {
                include(errorPageURL);
            }

            // 错误页面可以在一个 include中.
            Object newException =
                    request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);

            // t==null 表示属性未设置.
            if ((newException != null) && (newException == t)) {
                request.removeAttribute(RequestDispatcher.ERROR_EXCEPTION);
            }

            // 现在清除错误代码 - 防止双重处理.
            request.removeAttribute(RequestDispatcher.ERROR_STATUS_CODE);
            request.removeAttribute(RequestDispatcher.ERROR_REQUEST_URI);
            request.removeAttribute(RequestDispatcher.ERROR_SERVLET_NAME);
            request.removeAttribute(PageContext.EXCEPTION);

        } else {
            // 否则抛出一个ServletException.
            // 在ServletException中设置根异常, 来获取真正问题的堆栈跟踪
            if (t instanceof IOException)
                throw (IOException) t;
            if (t instanceof ServletException)
                throw (ServletException) t;
            if (t instanceof RuntimeException)
                throw (RuntimeException) t;

            Throwable rootCause = null;
            if (t instanceof JspException || t instanceof ELException) {
                rootCause =t.getCause();
            }

            if (rootCause != null) {
                throw new ServletException(t.getClass().getName() + ": "
                        + t.getMessage(), rootCause);
            }

            throw new ServletException(t);
        }
    }

    /**
     * 计算EL表达式的专有方法.
     * XXX - 一旦EL解释器移出JSTL并移动到自己的项目中, 这个方法将会消失. 现在, 这是必要的，因为标准机器太慢了.
     *
     * @param expression 待计算表达式
     * @param expectedType 预期结果类型
     * @param pageContext 页面上下文
     * @param functionMap Map, 映射前缀和名字到 Method
     * @return 计算的结果
     * @throws ELException If an error occurs during the evaluation
     */
    public static Object proprietaryEvaluate(final String expression,
            final Class<?> expectedType, final PageContext pageContext,
            final ProtectedFunctionMapper functionMap)
            throws ELException {
        final ExpressionFactory exprFactory = jspf.getJspApplicationContext(pageContext.getServletContext()).getExpressionFactory();
        ELContext ctx = pageContext.getELContext();
        ELContextImpl ctxImpl;
        if (ctx instanceof ELContextWrapper) {
            ctxImpl = (ELContextImpl) ((ELContextWrapper) ctx).getWrappedELContext();
        } else {
            ctxImpl = (ELContextImpl) ctx;
        }
        ctxImpl.setFunctionMapper(functionMap);
        ValueExpression ve = exprFactory.createValueExpression(ctx, expression, expectedType);
        return ve.getValue(ctx);
    }

    @Override
    public ELContext getELContext() {
        if (elContext == null) {
            elContext = applicationContext.createELContext(this);
            if (servlet instanceof JspSourceImports) {
                ImportHandler ih = elContext.getImportHandler();
                Set<String> packageImports = ((JspSourceImports) servlet).getPackageImports();
                if (packageImports != null) {
                    for (String packageImport : packageImports) {
                        ih.importPackage(packageImport);
                    }
                }
                Set<String> classImports = ((JspSourceImports) servlet).getClassImports();
                if (classImports != null) {
                    for (String classImport : classImports) {
                        ih.importClass(classImport);
                    }
                }
            }
        }
        return this.elContext;
    }
}
