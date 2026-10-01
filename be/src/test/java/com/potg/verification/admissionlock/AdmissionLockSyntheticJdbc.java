package com.potg.verification.admissionlock;

import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** In-memory JDBC protocol fixture, no DriverManager/network. It executes the actual probe path. */
final class AdmissionLockSyntheticJdbc implements AdmissionLockTiDbProbe.ConnectionFactory {
    final Map<String,List<Map<String,Object>>> tables=new LinkedHashMap<>();
    final Set<String> accounts=new HashSet<>();
    final List<Client> clients=new ArrayList<>();
    final String runtime,cleanup,schema;
    boolean schemaExists;
    long ids=0;
    Client lock;
    AdmissionLockSyntheticJdbc(Map<String,String> input) { runtime=input.get("runtimeAccount");cleanup=input.get("cleanupAccount");schema=input.get("schema"); }
    @Override public Connection open(String url,Properties p) throws SQLException {
        if(!url.endsWith("?sslMode=VERIFY_IDENTITY")||!"false".equals(p.getProperty("autoReconnect"))) throw new SQLException("SYNTHETIC_TLS_POLICY");
        String user=p.getProperty("user"); Client c=new Client(user.equals(runtime)?"runtime":user.equals(cleanup)?"cleanup":"admin",url.contains("/"+schema+"?"));clients.add(c);return c.connection;
    }
    final class Client {
        final String role; final Connection connection;
        boolean auto=true,closed,readOnly; int isolation=Connection.TRANSACTION_REPEATABLE_READ;
        String catalog; Map<String,List<Map<String,Object>>> before;
        Client(String role,boolean catalogSelected) { this.role=role;catalog=catalogSelected?schema:null;connection=proxy(Connection.class,this::call); }
        Object call(String n,Object[] a) throws Throwable {
            return switch(n) {
                case "prepareStatement","createStatement" -> statement(n.equals("prepareStatement")?(String)a[0]:null);
                case "getMetaData" -> metadata(this);
                case "getCatalog" -> catalog;
                case "setCatalog" -> { catalog=(String)a[0];yield null; }
                case "getAutoCommit" -> auto;
                case "setAutoCommit" -> { boolean next=(boolean)a[0]; if(auto&&!next) before=copy(); if(!auto&&next){ before=null;if(lock==this)lock=null; } auto=next;yield null; }
                case "isReadOnly" -> readOnly;
                case "setReadOnly" -> {readOnly=(boolean)a[0];yield null;}
                case "getTransactionIsolation" -> isolation;
                case "setTransactionIsolation" -> {isolation=(int)a[0];yield null;}
                case "commit" -> {before=copy();if(lock==this)lock=null;yield null;}
                case "rollback" -> {if(before!=null){tables.clear();tables.putAll(deepCopy(before));}before=copy();if(lock==this)lock=null;yield null;}
                case "close" -> {closed=true;if(lock==this)lock=null;yield null;}
                case "isClosed" -> closed;
                case "getWarnings","clearWarnings" -> null;
                case "toString" -> "SyntheticConnection";
                default -> primitiveDefault(Connection.class,n);
            };
        }
        PreparedStatement statement(String prepared) {
            Map<Integer,Object> bindings=new HashMap<>(); long[] generated={0}; ResultSet[] current={null};
            return proxy(PreparedStatement.class,(n,a)->{
                if(n.startsWith("set")&&a!=null&&a.length>=2&&a[0] instanceof Integer) {bindings.put((int)a[0],a[1]);return null;}
                if(n.equals("getConnection"))return connection;
                if(n.equals("getGeneratedKeys"))return result(List.of(row("GENERATED_KEY",generated[0])));
                if(n.equals("getResultSet"))return current[0];
                if(n.startsWith("execute")) {
                    String sql=prepared==null?(String)a[0]:prepared;
                    Object[] params=new Object[bindings.size()];for(int i=0;i<params.length;i++)params[i]=bindings.get(i+1);
                    if(n.equals("executeQuery")){current[0]=result(query(this,sql,params));return current[0];}
                    int count=change(this,sql,params,generated);
                    return n.equals("execute")?false:count;
                }
                if(n.equals("toString"))return "SyntheticStatement";
                return primitiveDefault(PreparedStatement.class,n);
            });
        }
    }
    private static Object primitiveDefault(Class<?> type,String name) {
        for(Method m:type.getMethods())if(m.getName().equals(name)) {
            Class<?> r=m.getReturnType();if(r==boolean.class)return false;if(r==int.class)return 0;if(r==long.class)return 0L;
        }
        return null;
    }
    private Map<String,List<Map<String,Object>>> copy(){return deepCopy(tables);}
    private static Map<String,List<Map<String,Object>>> deepCopy(Map<String,List<Map<String,Object>>> input){
        Map<String,List<Map<String,Object>>> out=new LinkedHashMap<>();input.forEach((k,v)->{List<Map<String,Object>> r=new ArrayList<>();for(var item:v)r.add(new LinkedHashMap<>(item));out.put(k,r);});return out;
    }
    private List<Map<String,Object>> query(Client c,String sql,Object[] args)throws SQLException {
        String q=sql.strip(),lower=q.toLowerCase(Locale.ROOT);
        if(lower.equals("select version()"))return List.of(row("version","8.0.11-TiDB-v8.5.4-private-identity"));
        if(lower.equals("select @@tidb_txn_mode"))return List.of(row("mode","pessimistic"));
        if(lower.equals("select utc_timestamp(6)"))return List.of(row("now",LocalDateTime.now(ZoneOffset.UTC)));
        if(lower.startsWith("show session status"))return List.of(row("Variable_name","Ssl_cipher","Value","TLS_AES_256_GCM_SHA384"));
        if(lower.startsWith("show grants")){String who=q.split("'")[1];if(!accounts.contains(who))throw new SQLException("SYNTHETIC_ACCOUNT_ABSENT","42000",1141);return List.of(row("grants","SYNTHETIC"));}
        if(lower.contains("information_schema.schemata"))return List.of(row("count",schemaExists?1L:0L));
        if(lower.contains("information_schema.columns"))return List.of(row("data_type","datetime","datetime_precision",6),row("data_type","datetime","datetime_precision",6));
        if(lower.contains("information_schema.tables")){
            if(lower.startsWith("select count"))return List.of(row("count",(long)tables.size()));
            List<Map<String,Object>> output=new ArrayList<>();
            if(lower.contains("engine")){for(String t:List.of("demo_capacity","demo_visit","demo_admission_lock"))if(tables.containsKey(t))output.add(row("table_name",t,"engine","InnoDB"));}
            else for(String t:tables.keySet())output.add(lower.contains("table_type")?row("TABLE_NAME",t,"TABLE_TYPE","BASE TABLE"):row("table_name",t));
            return output;
        }
        if(lower.contains("information_schema.key_column_usage")) {
            if(lower.contains("constraint_schema<>"))return List.of(row("count",0L));
            if(lower.startsWith("select count"))return List.of(row("count",4L));
            return List.of(relation("cards","user_id","users"),relation("transactions","card_id","cards"),relation("budgets","user_id","users"),relation("demo_visit","user_id","users"));
        }
        if(lower.startsWith("select count(*) as rows_count"))return List.of(row("rows_count",1L,"min_id",1,"max_id",1,"maximum",1,"visits",(long)tables.get("demo_visit").size(),"lock_rows",1L,"lock_min_id",1,"lock_max_id",1));
        if(lower.startsWith("select id,max_visitors,"))return List.of(row("id",1,"max_visitors",1,"visits",(long)tables.get("demo_visit").size(),"locks",1L,"minlock",1,"maxlock",1));
        if(lower.contains(" for update nowait")&&lower.contains("from demo_admission_lock")){
            if(lock!=null&&lock!=c)throw new SQLException("SYNTHETIC_NOWAIT","HY000",3572);lock=c;
        }
        int from=lower.indexOf(" from ");if(from<0)throw new SQLException("SYNTHETIC_QUERY_UNSUPPORTED");
        String table=lower.substring(from+6).split("\\s+")[0];List<Map<String,Object>> data=tables.get(table);
        if(data==null)throw new SQLException("SYNTHETIC_TABLE_ABSENT");
        List<Map<String,Object>> selected=new ArrayList<>();
        for(var r:data) {
            boolean keep=true;
            if(lower.contains(" where ")) {
                String w=lower.substring(lower.indexOf(" where ")+7);
                if(w.contains("is not null"))keep=r.entrySet().stream().anyMatch(e->java.util.regex.Pattern.compile("(?<![a-z0-9_])"+java.util.regex.Pattern.quote(e.getKey())+" is not null").matcher(w).find()&&e.getValue()!=null);
                else if(w.startsWith("created_at<="))keep=((LocalDateTime)r.get("created_at")).isBefore((LocalDateTime)args[0])&&((LocalDateTime)r.get("session_expires_at")).isBefore((LocalDateTime)args[1]);
                else if(w.contains("=?")){String key=w.substring(0,w.indexOf("=?"));keep=Objects.equals(r.get(key),args[0]);}
                else if(w.contains("=-1"))keep=false;
                else if(w.startsWith("id=1"))keep=((Number)r.get("id")).longValue()==1;
            }
            if(keep)selected.add(r);
        }
        String columns=q.substring(7,from).strip();
        if(columns.equalsIgnoreCase("COUNT(*)"))return List.of(row("count",(long)selected.size()));
        if(columns.equals("*"))return selected;
        List<Map<String,Object>> out=new ArrayList<>();for(var item:selected){Map<String,Object> values=new LinkedHashMap<>();for(String name:columns.split(","))values.put(name.strip(),item.get(name.strip().toLowerCase(Locale.ROOT)));out.add(values);}return out;
    }
    private Map<String,Object> relation(String table,String col,String parent){return row("TABLE_NAME",table,"COLUMN_NAME",col,"REFERENCED_TABLE_NAME",parent,"REFERENCED_COLUMN_NAME","id","DELETE_RULE","RESTRICT","UPDATE_RULE","RESTRICT","REFERENCED_TABLE_SCHEMA",schema,"CONSTRAINT_SCHEMA",schema);}
    private int change(Client c,String sql,Object[] args,long[] generated)throws SQLException {
        String lower=sql.strip().toLowerCase(Locale.ROOT);
        if(lower.startsWith("grant "))return 0;
        if(lower.startsWith("create database")){schemaExists=true;return 1;}
        if(lower.startsWith("drop database")){schemaExists=false;tables.clear();return 1;}
        if(lower.startsWith("create user")){accounts.add(sql.split("'")[1]);return 1;}
        if(lower.startsWith("drop user")){accounts.remove(sql.split("'")[1]);return 1;}
        if(lower.startsWith("create table")){tables.put(sql.split("\\s+")[2],new ArrayList<>());return 0;}
        String verb=lower.split("\\s+")[0];
        String table=lower.startsWith("insert ")?lower.substring(12).split("[ (]")[0]:lower.startsWith("delete ")?lower.substring(12).split(" ")[0]:lower.startsWith("alter ")?lower.split("\\s+")[2]:lower.split("\\s+")[1];
        if(!c.role.equals("admin")) {
            boolean allowed=verb.equals("update")?(table.equals("demo_admission_lock")||(c.role.equals("runtime")&&Set.of("users","transactions","budgets").contains(table)))
                :verb.equals("insert")?c.role.equals("runtime")&&Set.of("users","cards","transactions","budgets","demo_visit").contains(table)
                :verb.equals("delete")?c.role.equals("cleanup")&&Set.of("users","cards","transactions","budgets","demo_visit").contains(table):false;
            if(!allowed)throw new SQLException("SYNTHETIC_DENIED","42000",1142);
        }
        if(lower.contains("where 1=0")||lower.contains("=-1"))return 0;
        if(lower.startsWith("insert ")){
            Map<String,Object> r=new LinkedHashMap<>();String columns=sql.substring(sql.indexOf('(')+1,sql.indexOf(')'));String[] names=columns.split(",");
            if(!table.equals("demo_visit")){r.put("id",++ids);generated[0]=ids;}
            int index=0;
            for(String raw:names){String name=raw.strip();Object value;
                if(name.equals("id"))value=1L;
                else if(name.equals("max_visitors"))value=1000;
                else if(name.equals("created_at"))value=LocalDateTime.now(ZoneOffset.UTC);
                else if(name.equals("card_no")||name.equals("cvc"))value=null;
                else if(name.equals("is_overridden"))value=false;
                else value=args[index++];
                r.put(name,value);
            }
            if(table.equals("users"))r.put("file_id",null);
            if(table.equals("budgets")){r.put("initial_amount",null);r.put("initial_file_id",null);r.put("predicted_at",null);}
            tables.get(table).add(r);return 1;
        }
        if(lower.startsWith("update ")){
            int count=0;for(var r:tables.get(table)) {
                if(table.equals("demo_capacity")){r.put("max_visitors",1);count++;}
                else if(table.equals("transactions")&&Objects.equals(r.get("id"),args[1])){r.put("category",args[0]);count++;}
                else if(table.equals("demo_visit")&&Objects.equals(r.get("user_id"),args[0])){r.put("created_at",LocalDateTime.now(ZoneOffset.UTC).minusHours(25));r.put("session_expires_at",LocalDateTime.now(ZoneOffset.UTC).minusHours(1));count++;}
            }return count;
        }
        if(lower.startsWith("delete ")){
            String column=table.equals("demo_visit")?"user_id":"id";int before=tables.get(table).size();Set<Object> keys=new HashSet<>(Arrays.asList(args));tables.get(table).removeIf(r->keys.contains(r.get(column)));return before-tables.get(table).size();
        }
        throw new SQLException("SYNTHETIC_DML_UNSUPPORTED");
    }
    private DatabaseMetaData metadata(Client c){return proxy(DatabaseMetaData.class,(n,a)->switch(n){
        case "getConnection" -> c.connection;
        case "getDatabaseProductName" -> "MySQL";
        case "getColumns" -> result(columns((String)a[2]));
        case "getPrimaryKeys" -> result(List.of(row("COLUMN_NAME",a[2].equals("demo_visit")?"user_id":"id","KEY_SEQ",1)));
        case "getImportedKeys" -> result(a[2].equals("demo_visit")?List.of(row("PKTABLE_CAT",schema,"PKTABLE_NAME","users","PKCOLUMN_NAME","id","FKCOLUMN_NAME","user_id","KEY_SEQ",1,"DELETE_RULE",DatabaseMetaData.importedKeyRestrict,"UPDATE_RULE",DatabaseMetaData.importedKeyRestrict)):List.of());
        case "getIndexInfo" -> result(List.of(row("INDEX_NAME","idx_demo_visit_created_user","ORDINAL_POSITION",1,"COLUMN_NAME","created_at"),row("INDEX_NAME","idx_demo_visit_created_user","ORDINAL_POSITION",2,"COLUMN_NAME","user_id")));
        default -> primitiveDefault(DatabaseMetaData.class,n);
    });}
    private List<Map<String,Object>> columns(String table){
        Map<String,Integer> columns=table.equals("demo_visit")?Map.of("user_id",Types.BIGINT,"created_at",Types.TIMESTAMP,"scenario_version",Types.VARCHAR,"session_expires_at",Types.TIMESTAMP):table.equals("demo_capacity")?Map.of("id",Types.TINYINT,"max_visitors",Types.INTEGER):Map.of("id",Types.TINYINT);
        List<Map<String,Object>> rows=new ArrayList<>();columns.forEach((name,type)->rows.add(row("TABLE_NAME",table,"COLUMN_NAME",name,"DATA_TYPE",type,"NULLABLE",DatabaseMetaData.columnNoNulls,"IS_AUTOINCREMENT","NO","COLUMN_SIZE",name.equals("scenario_version")?16:0)));return rows;
    }
    private static ResultSet result(List<Map<String,Object>> rows){int[] cursor={-1};boolean[] wasNull={false};return proxy(ResultSet.class,(n,a)->{
        if(n.equals("next"))return ++cursor[0]<rows.size();if(n.equals("wasNull"))return wasNull[0];
        if(n.equals("getMetaData"))return proxy(ResultSetMetaData.class,(m,b)->switch(m) {
            case "getColumnCount" -> rows.isEmpty()?0:rows.getFirst().size();
            case "getColumnLabel","getColumnName" -> new ArrayList<>(rows.getFirst().keySet()).get((int)b[0]-1);
            case "getColumnType" -> Types.BIGINT;
            default -> primitiveDefault(ResultSetMetaData.class,m);
        });
        if(n.startsWith("get")&&a!=null&&a.length>0){Map<String,Object> r=rows.get(cursor[0]);Object v=a[0] instanceof Integer?new ArrayList<>(r.values()).get((int)a[0]-1):value(r,(String)a[0]);wasNull[0]=v==null;
            return switch(n){case "getLong"->v==null?0L:((Number)v).longValue();case "getInt"->v==null?0:((Number)v).intValue();case "getShort"->v==null?(short)0:((Number)v).shortValue();case "getString"->v==null?null:v.toString();default->v;};}
        return primitiveDefault(ResultSet.class,n);
    });}
    private static Object value(Map<String,Object> row,String name){for(var e:row.entrySet())if(e.getKey().equalsIgnoreCase(name))return e.getValue();return null;}
    static Map<String,Object> row(Object... pairs){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
    @FunctionalInterface interface Call {Object call(String name,Object[] args)throws Throwable;}
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,Call body){return(T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->m.getName().equals("equals")?p==a[0]:m.getName().equals("hashCode")?System.identityHashCode(p):body.call(m.getName(),a));}
}
