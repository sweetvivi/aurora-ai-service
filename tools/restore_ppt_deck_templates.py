import pymysql
from pathlib import Path

sql = Path(
    r"d:\work\java-workplace-vue3\backend-service\src\main\resources\db\migration\V54__ppt_agent_and_deck_templates.sql"
).read_text(encoding="utf-8")
chunk = sql.split("CREATE TABLE IF NOT EXISTS ppt_deck_templates", 1)[1]
create_sql = "CREATE TABLE IF NOT EXISTS ppt_deck_templates" + chunk.split("INSERT INTO ppt_deck_templates", 1)[0]

conn = pymysql.connect(host="127.0.0.1", user="root", password="root", database="backend", autocommit=True)
cur = conn.cursor()
cur.execute(create_sql)
print("ppt_deck_templates ready")

# ppt_jobs lease columns may still be missing if table was dropped/recreated oddly
cur.execute(
    """
    select column_name from information_schema.columns
     where table_schema = database() and table_name = 'ppt_jobs'
       and column_name in ('lease_owner', 'lease_until')
    """
)
print("lease cols", [r[0] for r in cur.fetchall()])
cur.execute("show tables like 'ppt_jobs'")
print("ppt_jobs", bool(cur.fetchall()))
cur.close()
conn.close()
